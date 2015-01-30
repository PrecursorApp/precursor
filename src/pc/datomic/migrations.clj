(ns pc.datomic.migrations
  (:require [pc.datomic :as pcd]
            [clojure.string]
            [clojure.tools.logging :as log]
            [datomic.api :refer [db q] :as d]
            [pc.models.doc :as doc-model]
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

;; TODO: need to re-run the conversion to double for any layers that transacted while we
;;       were moving properties back and forth. Can look at the tx-log for that.
;;       Run it twice, once before we move ident-double to ident and once after.
(defn layer-floats->doubles
  "Converts the attributes on the layer that should have been doubles, but were stupidly
   specified as floats (after converting from longs :()."
  [conn]
  (let [layer-attrs #{:start-x :start-y :end-x :end-y :stroke-width :opacity}
        idents (set (map (fn [a] (keyword "layer" (name a))) layer-attrs))
        layer-schemas (filter #(contains? idents (:db/ident %))
                              (pcd/touch-all '{:find [?t]
                                               :where [[?t :db/ident]]}
                                             (db conn)))
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
        new-schemas (for [s layer-schemas]
                      (merge s {:db/valueType :db.type/double
                                :db/id (d/tempid :db.part/db)
                                :db.install/_attribute :db.part/db}))
        db (db conn)]
    (when (seq layer-schemas) ;; don't try to migrate on a fresh db

      (log/info "creating temporary schema with :layer/attr-float for each attr")
      (println "creating temporary schema with :layer/attr-float for each attr")
      (time @(d/transact conn temp-schemas))

      (log/info "migrating float properties to the new double properties")
      (println "migrating float properties to the new double properties")
      (time (doseq [ident idents
                    :let [double-ident (ident->double ident)]]
              (println "converting" ident "to" double-ident)
              (dorun (map (fn [group]
                            @(d/transact conn (mapv (fn [[e v]]
                                                      [:db/add e double-ident (double v)])
                                                    group)))
                          (partition-all 1000
                                         (q '{:find [?t ?v] :in [$ ?ident]
                                              :where [[?t ?ident ?v]]}
                                            db ident))))))

      (log/info "renaming float attrs to new name")
      (println  "renaming float attrs to new name")
      (time @(d/transact conn (for [ident idents]
                               {:db/id ident
                                :db/ident (ident->float ident)})))

      (log/info "rename the double attrs to normal attrs")
      (println "rename the double attrs to normal attrs")
      (time @(d/transact conn (for [ident idents]
                               {:db/id (ident->double ident)
                                :db/ident ident}))))))


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

(defn add-prcrsr-bot
  "Adds a precursor bot"
  [conn]
  @(d/transact conn [{:db/id (d/tempid :db.part/user)
                      :cust/email "prcrsr-bot@prcrsr.com"
                      :cust/name "prcrsr"
                      :cust/uuid (d/squuid)}]))

;; TODO: figure out what to do with things like /document/1
(defn make-existing-documents-public
  "Marks all existing documents as public"
  [conn]
  (let [db (db conn)
        doc-ids (map first (d/q '{:find [?t]
                                  :where [[?t :document/name]]}
                                db))]
    (dorun (map-indexed
            (fn [i ids]
              (log/infof "making batch %s of %s public" i (/ (count doc-ids) 1000 1.0))
              @(d/transact conn (map (fn [id]
                                       {:db/id id
                                        :document/privacy :document.privacy/public})
                                     ids)))
            (partition-all 1000 doc-ids)))))

(defn change-document-id [conn db old-id new-id]
  (let [eids (map first (d/q '{:find [?t]
                               :in [$ ?old-id]
                               :where [[?t :document/id ?old-id]]}
                             db old-id))]
    (log/infof "migrating %s eids from %s to %s" (count eids) old-id new-id)
    @(d/transact conn (mapv (fn [eid] {:db/id eid :document/id new-id}) eids))))

(defn migrate-fake-documents
  "Migrates documents (i.e. chats, layers, transactions) with invalid document ids to valid document ids"
  [conn]
  (let [db (db conn)
        doc-ids (map first (d/q '{:find [?doc-id]
                                  :in [$]
                                  :where [[?t :document/id ?doc-id]
                                          [(missing? $ ?doc-id :document/name)]]}
                                db))]
    (dorun
     (map-indexed (fn [i invalid-id]
                    (let [new-doc (doc-model/create-public-doc! {:document/invalid-id invalid-id})]
                      (log/infof "migrating %s (%s of %s)" invalid-id i (count doc-ids))
                      (change-document-id conn db invalid-id (:db/id new-doc))))
                  doc-ids))))

(defn fix-text-bounding-boxes [db conn]
  (let [tx-count (atom 0)]
    (doseq [e (map first (d/q '[:find ?t
                                :where [?t :layer/type :layer.type/text]]
                              db))
            :let [layer (d/entity db e)]
            ;; These have the right height, so we'll assume they're already correct
            :when (not= -23.5 (- (:layer/end-y layer) (:layer/start-y layer)))]
      (let [new-end-x (+ (:layer/start-x layer) (* 11 (count (:layer/text layer))))
            new-end-y (+ (:layer/start-y layer) -23.5)]
        (try+
         @(d/transact conn [[:db.fn/cas e :layer/end-x (:layer/end-x layer) new-end-x]
                            [:db.fn/cas e :layer/end-y (:layer/end-y layer) new-end-y]])
         (swap! tx-count inc)
         (when (zero? (mod @tx-count 10000))
           (log/infof "sleeping for 30 seconds to give the transactor a rest (%s transactions)" @tx-count)
           (Thread/sleep (* 30 1000)))
         (catch Object t
           (if (pcd/cas-failed? t)
             (let [msg (format "cas failed for text layer with id %s" e)]
               (println msg)
               (log/error msg))
             (throw+))))))))

(defn parse-points [path]
  (partition-all 2 (map #(Float/parseFloat %) (re-seq #"[\d\.]+" path))))

(defn fix-pen-bounding-boxes [db conn]
  (let [tx-count (atom 0)]
    (doseq [e (map first (d/q '[:find ?t
                                :where [?t :layer/type :layer.type/path]]
                              db))
            :let [layer (d/entity db e)
                  points (some-> layer :layer/path parse-points)]
            :when (seq points)]
      (let [new-start-x (apply min (map first points))
            new-end-x (apply max (map first points))
            new-start-y (apply min (map second points))
            new-end-y (apply max (map last points))]
        (when-not (and (= new-start-x (:layer/start-x layer))
                       (= new-end-x (:layer/end-x layer))
                       (= new-start-y (:layer/start-y layer))
                       (= new-end-y (:layer/end-y layer)))
          (try+
           @(d/transact conn [[:db.fn/cas e :layer/start-x (:layer/start-x layer) new-start-x]
                              [:db.fn/cas e :layer/end-x (:layer/end-x layer) new-end-x]
                              [:db.fn/cas e :layer/start-y (:layer/start-y layer) new-start-y]
                              [:db.fn/cas e :layer/end-y (:layer/end-y layer) new-end-y]])
           (swap! tx-count inc)
           (when (zero? (mod @tx-count 10000))
             (log/infof "sleeping for 30 seconds to give the transactor a rest (%s transactions)" @tx-count)
             (Thread/sleep (* 30 1000)))
           (catch Object t
             (if (pcd/cas-failed? t)
               (let [msg (format "cas failed for pen layer with id %s" e)]
                 (println msg)
                 (log/error msg))
               (throw+)))))))))

(defn fix-bounding-boxes
  "Fixes bounding boxes for text and pen drawings"
  [conn]
  (time
   (let [db (db conn)]
     (log/info "fixing text bounding boxes")
     (fix-text-bounding-boxes db conn)
     (log/info "fixing pen bounding boxes")
     (fix-pen-bounding-boxes db conn))))

(def migrations
  "Array-map of migrations, the migration version is the key in the map.
   Use an array-map to make it easier to resolve merge conflicts."
  (array-map
   0 #'add-migrations-entity
   1 #'layer-longs->floats
   2 #'layer-child-uuid->long
   3 #'add-prcrsr-bot
   4 #'make-existing-documents-public
   5 #'migrate-fake-documents
   6 #'fix-bounding-boxes))

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
