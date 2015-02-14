(ns pc.datomic.migrations
  (:require [pc.datomic :as pcd]
            [clojure.string]
            [clojure.tools.logging :as log]
            [datomic.api :refer [db q] :as d]
            [pc.models.doc :as doc-model]
            [pc.datomic.migrations-archive :as archive]
            [slingshot.slingshot :refer (try+ throw+)]))

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

(defn add-prcrsr-bot
  "Adds a precursor bot"
  [conn]
  @(d/transact conn [{:db/id (d/tempid :db.part/user)
                      :cust/email "prcrsr-bot@prcrsr.com"
                      :cust/name "prcrsr"
                      :cust/uuid (d/squuid)}]))

(defn add-frontend-ids
  "Adds frontend ids to entities that need one"
  [conn]
  ;; assuming that only entities with document-ids need one. May
  ;; require another migration later if that's false.
  (let [db (d/db conn)]
    (dorun (pmap (fn [datoms]
                   @(d/transact-async conn
                                      (for [[doc-id datom-group] (group-by :v datoms)]
                                        [:temporary/assign-frontend-id (map :e datom-group) doc-id])))
                 (partition-all 100 (d/datoms db :aevt :document/id))))))

(def migrations
  "Array-map of migrations, the migration version is the key in the map.
   Use an array-map to make it easier to resolve merge conflicts."
  (array-map
   0 #'add-migrations-entity
   1 #'archive/layer-longs->floats
   2 #'archive/layer-child-uuid->long
   3 #'add-prcrsr-bot
   4 #'archive/make-existing-documents-public
   5 #'archive/migrate-fake-documents
   6 #'archive/fix-bounding-boxes))

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
