(ns pc.datomic.migrations
  (:require [pc.datomic :as pcd]
            [clojure.string]
            [clojure.tools.logging :as log]
            [datomic.api :refer [q] :as d]
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
  (let [e (migration-entity (d/db conn))]
    @(d/transact conn [[:db.fn/cas (:db/id e) :migration/version (dec version) version]])))

(defn add-migrations-entity
  "First migration, adds the entity that keep track of the migration version."
  [conn]
  (assert (= -1 (migration-version (d/db conn))))
  @(d/transact conn [{:db/id (d/tempid :db.part/user) :migration/version 0}]))

(defn add-prcrsr-bot
  "Adds a precursor bot"
  [conn]
  @(d/transact conn [{:db/id (d/tempid :db.part/user)
                      :cust/email "prcrsr-bot@prcrsr.com"
                      :cust/name "prcrsr"
                      :cust/uuid (d/squuid)}]))

;; TODO: need to re-run the conversion to double for any layers that transacted while we
;;       were moving properties back and forth. Can look at the tx-log for that.
;;       Run it twice, once before we move ident-double to ident and once after.
(defn layer-floats->doubles
  "Converts the attributes on the layer that should have been doubles, but were stupidly
   specified as floats (after converting from longs :()."
  [conn]
  ;; add migration metadata to transaction
  @(d/transact conn
               [{:db/doc "Annotates transaction with migration",
                 :db/cardinality :db.cardinality/one,
                 :db/id (d/tempid :db.part/db),
                 :db/ident :migration,
                 :db/valueType :db.type/ref,
                 :db.install/_attribute :db.part/db}
                {:db/id (d/tempid :db.part/user)
                 :db/ident :migration/layer-floats->doubles}])
  (let [layer-attrs #{:start-x :start-y :end-x :end-y :stroke-width :opacity :rx :ry}
        idents (set (map (fn [a] (keyword "layer" (name a))) layer-attrs))
        layer-schemas (filter #(contains? idents (:db/ident %))
                              (pcd/touch-all '{:find [?t]
                                               :where [[?t :db/ident]]}
                                             (d/db conn)))
        ident->float (fn [ident]
                       (keyword "layer" (str (name ident) "-float")))
        ident->double (fn [ident]
                        (keyword "layer" (str (name ident) "-double")))
        temp-schemas (for [s layer-schemas]
                       (-> s
                         (update-in [:db/ident] ident->double)
                         (merge {:db/valueType :db.type/double
                                 :db/id (d/tempid :db.part/db)
                                 :db.install/_attribute :db.part/db})))
        db (d/db conn)

        convert-in-progress (fn [start-db end-db]
                              (let [h (d/history (d/since end-db (d/basis-t start-db)))
                                    datoms (apply concat (map (partial d/datoms h :aevt) idents))
                                    ident (memoize (fn [a] (:db/ident (d/entity start-db a))))]
                                (doseq [[txid tx-data] (sort (group-by :tx datoms))]
                                  @(d/transact conn (conj (map (fn [d]
                                                                 [(if (:added d) :db/add :db/remove)
                                                                  (:e d)
                                                                  (ident->double (ident (:a d)))
                                                                  (double (:v d))])
                                                               tx-data)
                                                          {:db/id (d/tempid :db.part/tx)
                                                           :migration :migration/layer-floats->doubles})))))]

    (log/info "creating temporary schema with :layer/attr-float for each attr")
    (println "creating temporary schema with :layer/attr-float for each attr")
    (time @(d/transact conn temp-schemas))

    (log/info "migrating float properties to the new double properties")
    (println "migrating float properties to the new double properties")
    (time (doseq [ident idents
                  :let [double-ident (ident->double ident)]]
            (println "converting" ident "to" double-ident)
            @(d/transact conn [{:db/id (d/tempid :db.part/user)
                                :layer/type :layer.type/rect
                                :layer/name "Test layer"
                                :layer/start-x 88.1
                                :layer/end-x 98.3
                                :layer/start-y 82.3
                                :layer/end-y 92.3}])
            (dorun (pmap (fn [group]
                           @(d/transact-async conn
                                              (conj (map (fn [[e v]]
                                                           [:db/add e double-ident (double v)])
                                                         group)
                                                    {:db/id (d/tempid :db.part/tx)
                                                     :migration :migration/layer-floats->doubles})))
                         (partition-all 100
                                        (q '{:find [?t ?v] :in [$ ?ident]
                                             :where [[?t ?ident ?v]]}
                                           db ident))))))

    (log/info "converting transacted while we were migrating")
    (let [next-db (d/db conn)]
      (convert-in-progress db next-db)
      (let [nnext-db (d/db conn)]
        (convert-in-progress next-db nnext-db)


        (log/info "renaming float attrs to new name")
        (println  "renaming float attrs to new name")
        (time @(d/transact conn (conj
                                 (for [ident idents]
                                   {:db/id ident
                                    :db/ident (ident->float ident)})
                                 {:db/id (d/tempid :db.part/tx)
                                  :migration :migration/layer-floats->doubles})))

        (log/info "rename the double attrs to normal attrs")
        (println "rename the double attrs to normal attrs")
        (let [t (time @(d/transact conn (conj
                                         (for [ident idents]
                                           {:db/id (ident->double ident)
                                            :db/ident ident})
                                         {:db/id (d/tempid :db.part/tx)
                                          :migration :migration/layer-floats->doubles})))]
          (convert-in-progress nnext-db (:db-before t)))))))

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
  (drop (inc (migration-version (d/db conn))) migrations))

(defn run-necessary-migrations [conn]
  (doseq [[version migration] (necessary-migrations conn)]
    (log/infof "migrating datomic db to version %s with %s" version migration)
    (migration conn)
    (when (pos? version)
      (update-migration-version conn version))))

(defn init []
  (run-necessary-migrations (pcd/conn)))
