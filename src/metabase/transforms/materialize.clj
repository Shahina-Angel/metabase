(ns metabase.transforms.materialize
  (:require [metabase.api.common :as api]
            [metabase.models
             [card :as card :refer [Card]]
             [collection :as collection]
             [table :refer [Table]]]
            [metabase.query-processor.middleware
             [annotate :as qp.annotate]
             [add-implicit-clauses :as qp.imlicit-clauses]]
            [metabase.util :as u]
            [toucan.db :as db]))

(defn infer-cols
  "Infer column types from given (MBQL) query."
  [query]
  (-> {:query query
       :type  :query}
      qp.imlicit-clauses/add-implicit-mbql-clauses
      (#'qp.annotate/add-column-info* nil)
      :cols))

(defn ->source-table
  "Serialize `entity` into a form suitable as `:source-table` value."
  [entity]
  (if (instance? (type Table) entity)
    (u/get-id entity)
    (str "card__" (u/get-id entity))))

(declare get-or-create-root-container-collection!)

(defn- root-container-location
  []
  (collection/children-location
   (db/select-one ['Collection :location :id]
     :id (get-or-create-root-container-collection!))))

(defn- get-collection
  ([name]
   (get-collection name (root-container-location)))
  ([name location]
   (db/select-one-id 'Collection
     :name     name
     :location location)))

(defn- create-collection!
  ([name color description]
   (create-collection! name color description (root-container-location)))
  ([name color description location]
   (u/get-id
    (db/insert! 'Collection
      {:name        name
       :color       color
       :description description
       :location    location}))))

(defn- get-or-create-root-container-collection!
  "Get or create container collection for transforms in the root collection."
  []
  (let [location "/"
        name     "Automatically Generated Transforms"]
    (or (get-collection name location)
        (create-collection! name "#509EE3" nil location))))

(defn fresh-collection-for-transform!
  "Create a new collection for all the artefacts belonging to transform, or reset it if it already
   exists."
  [{:keys [name description]}]
  (if-let [collection-id (get-collection name)]
    (db/delete! Card :collection_id collection-id)
    (create-collection! name "#509EE3" description)))

(defn make-card!
  "Make and save a card with a given name, query, and description."
  [name query description]
  (->> {:creator_id             api/*current-user-id*
        :dataset_query          {:query    (update query :source-table ->source-table)
                                 :type     :query
                                 :database ((some-fn :db_id :database_id) (:source-table query))}
        :description            description
        :name                   name
        :collection_id          (get-collection name)
        :result_metadata        (infer-cols query)
        :visualization_settings {}
        :display                :table}
       card/populate-query-fields
       (db/insert! Card)))
