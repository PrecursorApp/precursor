(ns pc.datomic.migrations
  (:require [pc.datomic :as pcd]
            [clojure.string]
            [clojure.tools.logging :as log]
            [clj-time.core :as time]
            [clj-time.coerce]
            [datomic.api :refer [db q] :as d]
            [pc.models.cust :as cust-model]
            [pc.models.doc :as doc-model]
            [pc.models.permission :as permission-model]
            [pc.datomic.migrations-archive :as archive]
            [pc.datomic.web-peer :as web-peer]
            [slingshot.slingshot :refer (try+ throw+)])
  (:import java.util.UUID))

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

(defn doc-id-datom->ref-transaction [db {:keys [e v] :as datom}]
  (let [ent (d/entity db e)]
    (cond (:db/txInstant ent) [:db/add e :transaction/document v]
          (:layer/type ent) [:db/add e :layer/document v]
          (:chat/body ent)  [:db/add e :chat/document v]
          (:layer/name ent) [:db/add e :layer/document v]
          (= :layer (:entity/type ent)) [:db/add e :layer/document v]
          (:layer/end-x ent) [:db/add e :layer/document v]
          ;; TODO: need to clean up the db to get rid of invalid layers and do some sort of
          ;;       validation when new fields come through the wire
          (= #{:frontend/id :document/id} (set (keys ent))) [:db.fn/retractEntity e]
          :else (throw+ {:error :unknown-document-id-type :e e}))))

(defn have-document-ref? [db {:keys [e] :as datom}]
  (let [ent (d/entity db e)]
    (or (:layer/document ent)
        (:chat/document ent)
        (:transaction/document ent))))

(defn document-ids->document-refs [db conn]
  (log/infof "converting document-ids to document-refs")
  (doseq [datom-group (partition-all 1000 (remove (partial have-document-ref? db) (d/datoms db :avet :document/id)))]
    @(d/transact-async conn (conj (mapv #(doc-id-datom->ref-transaction db %) datom-group)
                                  {:db/id (d/tempid :db.part/tx)
                                   :transaction/source :transaction.source/migration
                                   :migration :migration/longs->refs}))))

(defn have-ref-attr? [db ref-attr {:keys [e] :as datom}]
  (let [ent (d/entity db e)]
    (get ent ref-attr)))

(defn access-entities-ids->refs [db conn]
  (doseq [attr [:permission/document :permission/cust :permission/granter
                :access-request/document :access-request/cust
                :access-grant/document :access-grant/granter]
          :let [ref-attr (keyword (namespace attr) (str (name attr) "-ref"))]]
    (log/infof "converting %s to %s" attr ref-attr)
    (doseq [datom-group (partition-all 1000 (remove (partial have-ref-attr? db ref-attr) (d/datoms db :aevt attr)))]
      @(d/transact-async conn (conj (mapv (fn [{:keys [e v] :as datom}]
                                            [:db/add e ref-attr v])
                                          datom-group)
                                    {:db/id (d/tempid :db.part/tx)
                                     :transaction/source :transaction.source/migration
                                     :migration :migration/longs->refs})))))

(defn longs->refs
  "Converts attributes that store ids to refs, e.g. layers go from :document/id to :layer/document"
  [conn]
  (let [db (d/db conn)]
    (document-ids->document-refs db conn)
    (access-entities-ids->refs db conn)))

(defn add-prcrsr-bot-color
  "Gives precursor bot the stable green color"
  [conn]
  (let [prcrsr-bot-id (d/q '{:find [?t .]
                             :in [$ ?email]
                             :where [[?t :cust/email ?email]]}
                           (d/db conn) "prcrsr-bot@prcrsr.com")]
    @(d/transact conn [[:db/add prcrsr-bot-id :cust/color-name :color.name/green]])))

(defn add-team-plans [conn]
  (let [db (d/db conn)]
    (doseq [team (map #(d/entity db %) (d/q '[:find [?t ...] :where [?t :team/subdomain]] db))
            :let [planid (d/tempid :db.part/user)]]
      @(d/transact conn [{:db/id (d/tempid :db.part/tx)
                          :migration :migration/add-team-plans
                          :transaction/source :transaction.source/migration}
                         {:db/id (:db/id team)
                          :team/plan {:db/id planid
                                      :plan/trial-end (clj-time.coerce/to-date (time/plus (time/now) (time/weeks 2)))}}
                         (web-peer/server-frontend-id planid (:db/id team))]))))

(defn ensure-team-creators [conn]
  (let [db (d/db conn)]
    (doseq [team (map #(d/entity db %) (d/q '[:find [?t ...] :where [?t :team/subdomain] (not [?t :team/creator])] db))]
      @(d/transact conn [{:db/id (d/tempid :db.part/tx)
                          :migration :migration/ensure-team-creators
                          :transaction/source :transaction.source/migration}
                         {:db/id (:db/id team)
                          :team/creator (->> team
                                          (permission-model/find-by-team db)
                                          (filter :permission/cust-ref)
                                          (sort-by :permission/grant-date)
                                          first
                                          :permission/cust-ref
                                          :db/id)}]))))

(defn add-access-grant-indexes [conn]
  @(d/transact conn [{:db/id (d/tempid :db.part/tx)
                      :transaction/source :transaction.source/migration}
                     {:db/id :access-grant/token
                      :db/index true
                      :db.alter/_attribute :db.part/db}
                     {:db/id :access-grant/email
                      :db/index true
                      :db.alter/_attribute :db.part/db}])
  @(d/transact conn [{:db/id (d/tempid :db.part/tx)
                      :transaction/source :transaction.source/migration}
                     {:db/id :access-grant/token
                      :db/unique :db.unique/value
                      :db.alter/_attribute :db.part/db}]))

(defn add-titles-to-issue-docs [conn]
  (let [db (d/db conn)
        issues-and-docs (filter #(= "Untitled" (:document/name (:doc %)))
                                (map (fn [d]
                                       {:doc (d/entity db (:v d))
                                        :issue (d/entity db (:e d))})
                                     (d/datoms db :aevt :issue/document)))]
    @(d/transact conn (map (fn [{:keys [doc issue]}]
                             [:db/add (:db/id doc) :document/name (:issue/title issue)])
                           issues-and-docs))))

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
   6 #'archive/fix-bounding-boxes
   7 #'archive/add-frontend-ids
   8 #'longs->refs
   9 #'add-prcrsr-bot-color
   10 #'add-team-plans
   11 #'ensure-team-creators
   12 #'add-access-grant-indexes
   13 #'add-titles-to-issue-docs))

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
