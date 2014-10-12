(ns pc.datomic.migrations
  (:require [pc.datomic :as pcd]
            [clojure.string]
            [clojure.tools.logging :as log]
            [datomic.api :refer [db q] :as d]))

(defn migration-entity
  "Finds the entity that keeps track of the migration version, there should
   be only one of these."
  [db]
  (pcd/touch-one '{:find [?t]
                   :where [[?t :migration/version]]}
                 db))

(defn migration-version
  "Gets the migration version, or returns -1 if no migrations have run."
  [db]
  (get (migration-entity db)
       :migration/version
       -1))

(defn ensure-migration-schema
  "Ensures that the schema for storing the migration entity exists."
  [conn]
  @(d/transact conn [{:db/id (d/tempid :db.part/db)
                      :db/ident :migration/version
                      :db/valueType :db.type/long
                      :db.install/_attribute :db.part/db
                      :db/cardinality :db.cardinality/one}]))

(defn update-migration-version
  "Updates the migration version, throwing an exception if the version does not increase
   the previous version by 1."
  [conn version]
  (let [e (migration-entity (db conn))]
    @(d/transact conn [[:db.fn/cas (:db/id e) :migration/version (dec version) version]])))

(defn add-migrations-entity
  "First migration, adds the entity that keep track of the migration version."
  [conn]
  (assert (= -1 (migration-version (db conn))))
  @(d/transact conn [{:db/id (d/tempid :db.part/user) :migration/version 0}]))

(defn layer-longs->floats
  "Converts the attributes on the layer that should have been floats, but were mistakenly
   specified as longs."
  [conn]
  (let [layer-attrs #{:start-x :start-y :end-x :end-y :stroke-width :opacity
                      :start-sx :start-sy :current-sx :current-sy}
        idents (set (map (fn [a] (keyword "layer" (name a))) layer-attrs))
        layer-schemas (filter #(contains? idents (:db/ident %))
                              (pcd/touch-all '{:find [?t]
                                               :where [[?t :db/ident]]}
                                             (db conn)))
        ident->float (fn [ident]
                       (keyword "layer" (str (name ident) "-float")))
        ident->long (fn [ident]
                       (keyword "layer" (str (name ident) "-long")))
        temp-schemas (for [s layer-schemas]
                       (-> s
                           (update-in [:db/ident] ident->float)
                           (merge {:db/valueType :db.type/float
                                   :db/id (d/tempid :db.part/db)
                                   :db.install/_attribute :db.part/db})))
        new-schemas (for [s layer-schemas]
                      (merge s {:db/valueType :db.type/float
                                :db/id (d/tempid :db.part/db)
                                :db.install/_attribute :db.part/db}))
        db (db conn)]
    (when (seq layer-schemas) ;; don't try to migrate on a fresh db

      ;; create temporary schema with :layer/attr-float for each attr
      @(d/transact conn temp-schemas)

      ;; migrate long properties to the new float properties
      (doseq [ident idents]
        (dorun (map (fn [[e]]
                      (let [new-val (-> (d/entity db e) (get ident) float)]
                        @(d/transact conn [[:db/add e (ident->float ident) new-val]])))
                    (q '{:find [?t] :in [$ ?ident]
                         :where [[?t ?ident]]}
                       db ident))))

      ;; rename the long attrs to new name
      @(d/transact conn (for [ident idents]
                          {:db/id ident
                           :db/ident (ident->long ident)}))

      ;; rename the float attrs to normal attrs
      @(d/transact conn (for [ident idents]
                          {:db/id (ident->float ident)
                           :db/ident ident})))))


(defn layer-child-uuid->long
  "Converts the child type from uuids to long"
  [conn]
  ;; don't try to migrate on a fresh db
  (when (pcd/touch-one '{:find [?t]
                         :where [[?t :db/ident :layer/child]
                                 [?t :db/valueType :db.type/uuid]]}
                       (db conn))
    ;; rename the uuid attr to a new name
    @(d/transact conn [{:db/id :layer/child
                        :db/ident :layer/child-deprecated}])))


(def migrations
  "Array-map of migrations, the migration version is the key in the map.
   Use an array-map to make it easier to resolve merge conflicts."
  (array-map
   0 #'add-migrations-entity
   1 #'layer-longs->floats
   2 #'layer-child-uuid->long))

(defn necessary-migrations
  "Returns tuples of migrations that need to be run, e.g. [[0 #'migration-one]]"
  [conn]
  (ensure-migration-schema conn)
  (drop (inc (migration-version (db conn))) migrations))

(defn run-necessary-migrations [conn]
  (doseq [[version migration] (necessary-migrations conn)]
    (log/infof "migrating datomic db to version %s with %s" version migration)
    (migration conn)
    (when (pos? version)
      (update-migration-version conn version))))

(defn init []
  (run-necessary-migrations (pcd/conn)))
