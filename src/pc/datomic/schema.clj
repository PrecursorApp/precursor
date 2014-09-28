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

   (attribute :layer/uuid
              :db.type/uuid
              :db/index true
              :db/doc "Uuid set on the frontend when it creates the layer")

   (attribute :layer/type
              :db.type/ref
              :db/doc "Layer type")
   ;; TODO: more layer types
   (enum :layer.type/rect)
   (enum :layer.type/group)

   (attribute :layer/start-x
              :db.type/long)

   (attribute :layer/start-y
              :db.type/long)

   (attribute :layer/end-x
              :db.type/long)

   (attribute :layer/end-y
              :db.type/long)

   (attribute :layer/fill
              :db.type/string)

   (attribute :layer/stroke-width
              :db.type/long)

   (attribute :layer/stroke-color
              :db.type/string)

   ;; Use the layer's uuid so that the frontend can track children
   ;; Wonder what happens if we make a layer a child of one of its children...
   (attribute :layer/child
              :db.type/uuid
              :db/cardinality :db.cardinality/many
              :db/doc "Layer's children")

   ;; No logins at the moment, so we'll use this to identify users
   (attribute :session/uuid
              :db.type/uuid
              :db/index true)

   (attribute :document/uuid
              :db.type/uuid
              :db/index true)

   (attribute :document/name
              :db.type/string)

   (attribute :dummy
              :db.type/ref)
   (enum :dummy/dummy)

   (attribute :document/id
              :db.type/long
              :db/index true
              :db/doc "Document entity id")

   ])

(defn ensure-schema
  ([] (ensure-schema (pcd/conn)))
  ([conn]
     @(d/transact conn schema)))

(defn init []
  (ensure-schema))
