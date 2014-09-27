(ns pc.datomic.schema
  (:require [pc.datomic :as pcd]
            [datomic.api :refer [db q] :as d]))

(defn attribute [ident type & {:as opts}]
  (merge {:db/id (d/tempid :db.part/db)
          :db/ident ident
          :db/valueType type
          :db.install/_attribute :db.part/db}
         {:db/cardinality :db.cardinality/one}
         opts))

(defn enum [ident]
  {:db/id (d/tempid :db.part/user)
   :db/ident ident})

(def schema
  [
   (attribute :layer/name
              :db.type/string
              :db/doc "Layer name")

   (attribute :layer/type
              :db.type/ref
              :db/doc "Layer type")
   ;; TODO: more layer types
   (enum :layer.type/rect)
   (enum :layer.type/group)

   (attribute :layer/fill
              :db.type/bytes
              :db/doc "Fill, should be an array of ints, has to be converted to and from bytes")

   ;; I think that this is how you define relationships between entities
   ;; Wonder what happens if we make a layer a child of one of its children...
   (attribute :layer/child
              :db.type/ref
              :db/cardinality :db.cardinality/many)
   ])

(defn ensure-schema
  ([] (ensure-schema (pcd/conn)))
  ([conn]
     @(d/transact conn schema)))

(defn init []
  (ensure-schema))
