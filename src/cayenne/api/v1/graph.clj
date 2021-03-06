(ns cayenne.api.v1.graph
  (:require [cayenne.conf :as conf]
            [cayenne.util :refer [update-vals parse-int-safe]]
            [cayenne.api.v1.types :as t]
            [cayenne.api.v1.response :as r]
            [cayenne.api.v1.query :as q]
            [cayenne.data.work :as work]
            [cayenne.ids.doi :as doi-id]
            [cayenne.ids.issn :as issn-id]
            [cayenne.ids.orcid :as orcid-id]
            [cayenne.tasks.datomic :as cd]
            [datomic.api :as d]
            [liberator.core :refer [defresource]]
            [compojure.core :refer [defroutes ANY]]
            [ring.util.response :refer [redirect]]
            [clojure.string :as string]
            [bigml.sampling.simple :as simple]))

;; Implements the graph API

;; Handle resolution of IDs of implicit type

(defn id-type [s]
  (cond
   (doi-id/extract-long-doi s) :doi
   (orcid-id/extract-orcid s) :orcid
   (issn-id/extract-issn s) :issn))

(defn typed-id [s]
  (condp = (id-type s)
    :orcid {:type :orcid 
            :urn (orcid-id/to-orcid-uri s)
            :id (orcid-id/normalize-orcid s)}
    :doi {:type :doi 
          :urn (doi-id/to-long-doi-uri s)
          :id (doi-id/normalize-long-doi s)}
    :issn {:type :issn 
           :urn (issn-id/to-issn-uri s)
           :id (issn-id/normalize-issn s)}))

;; TODO only working for DOIs
(defn node-link [s context]
  "Turn an implicit type ID/URN into a graph API node link."
  [s]
  (let [context-path (get-in context [:request :context])]
    (cond
     (doi-id/extract-long-doi s) 
     (str context-path "/doi/" (doi-id/normalize-long-doi s))
     (orcid-id/extract-orcid s) 
     (str context-path "/orcid/" (orcid-id/normalize-orcid s))
     (issn-id/extract-issn s) 
     (str context-path "/issn/" (issn-id/normalize-issn s)))))

;; Our query definitions

(def ^:dynamic query-db nil)

;; TODO make this return related entity info rather than looking that up separately
(defn urn-related [urn-value relation]
  (d/q '[:find ?related-urn-value
         :in $ ?urn-value ?relation
         :where
         [?target-urn :urn/value ?urn-value]
         [?target-urn ?relation ?related-urn]
         [?related-urn :urn/value ?related-urn-value]]
       query-db
       urn-value
       relation))

(defn urn-relations [urn-value]
  (->> cd/relation-types
       (map #(vector % (->> % (urn-related urn-value) vec flatten)))
       (into {})))

(defn urn-type [urn-value]
  (d/q '[:find ?ident
         :in $ ?urn-value
         :where
         [?target-urn :urn/value ?urn-value]
         [?target-urn :urn/type ?urn-type]
         [?urn-type :db/ident ?ident]]
       query-db
       urn-value))

(defn urn-entity-type [urn-value]
  (d/q '[:find ?ident
         :in $ ?urn-value
         :where
         [?target-urn :urn/value ?urn-value]
         [?target-urn :urn/entityType ?urn-entity-type]
         [?urn-entity-type :db/ident ?ident]]
       query-db
       urn-value))

(defn urn-source [urn-value]
  (d/q '[:find ?ident
         :in $ ?urn-value
         :where
         [?target-urn :urn/value ?urn-value]
         [?target-urn :urn/source ?urn-source]
         [?urn-source :db/ident ?ident]]
       query-db
       urn-value))

(defn urn-name [urn-value]
  (d/q '[:find ?urn-name
         :in $ ?urn-value
         :where
         [?target-urn :urn/value ?urn-value]
         [?target-urn :urn/name ?urn-name]]
       query-db
       urn-value))

;; TODO from-update-date, until-update-date
;;      handle multiple relations
(defn lookup-context
  "Handles query, filters and rels."
  [qc]
  (let [query 
        {:find 
         '[?urn-value ?related-urn-value]
         :where
         (concat
          (mapcat
           #(if-not (:any %)
              [['?urn (:rel %) '?related-urn]
               ['?related-urn :urn/value (:value %)]]
              [['?urn (:rel %) '?related-urn]])
           (:rels qc))
          (when-let [source (-> qc (get-in [:filters "source"]) first)]
            (if (= "none" source)
              [['(missing? $ ?urn :urn/source)]]
              [['?urn :urn/source (keyword (str "urn.source/" source))]]))
          (when-let [related-source (-> qc (get-in [:filters "related-source"]) first)]
            (if (= "none" related-source)
              [['(missing? $ ?related-urn :urn/source)]]
              [['?related-urn :urn/source (keyword (str "urn.source/" related-source))]]))
          '[[?urn :urn/value ?urn-value]
           [?related-urn :urn/value ?related-urn-value]])}]
    (prn qc)
    (prn query)
    (d/q query query-db)))

(defn get-urn-id [urn]
  (-> (d/q '[:find ?urn
             :in $ ?urn-value
             :where
             [?urn :urn/value ?urn-value]]
           query-db urn)
      ffirst))

(defn find-paths [eid depth neighbors target? path located]
  (if (zero? depth)
    located
    (reduce #(find-paths %2 (dec depth) neighbors target? (conj path eid) %1)
            (if (target? eid)
              (conj located (conj path eid))
              located)
            (neighbors eid))))

(defn find-updates [doi depth]
  (letfn [(updated? [eid]
            (not (empty? (d/datoms query-db :eavt eid :isUpdatedBy))))
          (cites [eid]
            (map :v (d/datoms query-db :eavt eid :cites)))]            
    (find-paths (get-urn-id doi) depth cites updated? [] [])))

;; Take our datomic queries above and turn results into presentable 
;; documents. Here, we bind our query database.

(declare describe-urn)

(defn describe-relations [rels context]
  (update-vals rels (keys rels) (partial map #(describe-urn % context))))

(defn describe-urn 
  "Describe an URN, or return nil if there is no ID type associated
   with the URN (indicates we've never seen it described, nor have
   relations to it.)"
  [urn-value context & {:keys [relations] :or {relations false}}]
  (binding [query-db (d/db (conf/get-service :datomic))]
    (when-let [type (-> urn-value urn-type ffirst)]
      (let [entity-type (-> urn-value urn-entity-type ffirst)
            source (-> urn-value urn-source ffirst)
            label (-> urn-value urn-name ffirst)]
        (cond-> 
         {:type (name type)
          :link (node-link urn-value context)
          :urn urn-value}
         label (assoc :label label)
         relations (assoc :rel (-> urn-value 
                                   urn-relations 
                                   (describe-relations context)))
         source (assoc :source (name source))
         entity-type (assoc :entity-type (name entity-type)))))))

(defn ->no-cr-citations [urn]
  (if (= "crossref" (:source urn))
    (-> urn
        (update-in [:rel :cites] (fn [urns] (filter #(= (:source %) "datacite") urns)))
        (update-in [:rel :isCitedBy] (fn [urns] (filter #(= (:source %) "datacite") urns))))
    urn))

(defn search-relations [query-context]
  (binding [query-db (d/db (conf/get-service :datomic))]
    (lookup-context query-context)))

(defn select-relations [relations query-context]
  (if (pos? (:sample query-context))
    (->> relations
         simple/sample
         (take (:sample query-context)))
    (->> relations
         ;;(sort-by first) ; TODO sort order
         (drop (:offset query-context))
         (take (:rows query-context)))))

(defn get-urn-for-eid [eid]
  (-> (d/datoms (d/db (conf/get-service :datomic)) :eavt eid :urn/value)
      first
      :v))

(defn get-updates-for-eid [eid]
  (map
   #(-> % :v get-urn-for-eid work/fetch-one :message)
   (d/datoms (d/db (conf/get-service :datomic)) :eavt eid :isUpdatedBy)))

(defn describe-updates [doi depth context]
  (binding [query-db (d/db (conf/get-service :datomic))]
    (->> (find-updates doi depth)
         (map 
          #(hash-map
            :citation-path
            (map (fn [eid] 
                   (-> eid 
                       get-urn-for-eid
                       (describe-urn context)))
                 %)
            :update
            (-> % last get-updates-for-eid))))))

;; Wrap our responses in standard response containers

(defn node-response [context] 
  (r/api-response :node :content (:urn context)))

(defn node-list-response [query-context]
  (let [relations (search-relations query-context)]
    (-> (r/api-response :node-list)
        (r/with-result-items 
          (count relations)
          (select-relations relations query-context))
        (r/with-query-context-info query-context))))

;; Define our resources

(defresource graph-doi-resource [doi]
  :allowed-methods [:get :options]
  :available-media-types t/json
  :exists? #(hash-map 
             :urn (-> doi 
                      doi-id/to-long-doi-uri 
                      (describe-urn % :relations true)
                      ->no-cr-citations))
  :handle-ok node-response)

(defresource graph-orcid-resource [orcid]
  :allowed-methods [:get :options]
  :available-media-types t/json
  :exists? #(hash-map 
             :urn (-> orcid 
                      orcid-id/to-orcid-uri 
                      (describe-urn % :relations true)))
  :handle-ok node-response)

(defresource graph-issn-resource [issn]
  :allowed-methods [:get :options]
  :available-media-types t/json
  :exists? #(hash-map
             :urn (-> issn 
                      issn-id/to-issn-uri 
                      (describe-urn % :relations true)))
  :handle-ok node-response)

(defresource update-analysis-resource [doi depth]
  :allowed-methods [:get :options]
  :available-media-types t/json
  :exists? #(hash-map
             :report (-> doi
                         doi-id/to-long-doi-uri
                         (describe-updates (parse-int-safe depth) %)))
  :handle-ok #(r/api-response :update-report :content (:report %)))

(defresource dispatch-resource [query]
  :allowed-methods [:get :options]
  :available-media-types t/json
  :exists? {:info (typed-id query)}
  :handle-ok #(redirect 
               (str (name (get-in % [:info :type]))
                    "/" 
                    (get-in % [:info :id]))))

(defresource graph-resource []
  :allowed-methods [:get :options]
  :available-media-types t/json
  :handle-ok #(-> % q/->query-context node-list-response))

;; Define how we route paths to resources

(defroutes graph-api-routes
  (ANY "/doi/*" {{doi :* depth :depth} :params}
       (if (.endsWith  doi "/updates")
         (update-analysis-resource
          (string/replace doi #"/updates\z" "") depth)
         (graph-doi-resource doi)))
  (ANY "/orcid/*" {{orcid :*} :params}
       (graph-orcid-resource orcid))
  (ANY "/issn/*" {{issn :*} :params}
       (graph-issn-resource issn))
  (ANY "/dispatch" {{query :q} :params} 
       (dispatch-resource query))
  (ANY "/dispatch/*" {{query :*} :params}
       (dispatch-resource query))
  (ANY "/" []
       (graph-resource)))
