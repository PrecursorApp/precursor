(ns pc.datomic
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :refer (infof)]
            [datomic.api :refer [db q] :as d])
  (:import java.util.UUID))

(def default-uri "datomic:free://localhost:4334/pc2")

(defn conn [& {:keys [uri]}]
  (d/connect (or uri default-uri)))

(defn default-db []
  (db (conn)))

(defn retract-entities
  "retractEntity all entities matching query"
  [conn query]
  @(d/transact conn (for [r (q query (db conn))]
                      [:db.fn/retractEntity (first r)])))

(defn touch+
  "By default, touch returns a map that can't be assoc'd. Fix it"
  [ent]
  ;; (into {}) makes the map assoc'able, but lacks a :db/id, which is annoying for later lookups.
  (into (select-keys ent [:db/id]) (d/touch ent)))

(defn entity+
  [db eid]
  (cond
   (integer? eid) (d/entity db eid)
   (:db/id eid) (d/entity db (:db/id eid))))

(defn touch-all
  "Runs the query that returns [[eid][eid]] and returns all entity maps.
   Uses the first DB to look up all entities"
  [query & query-args]
  (let [the-db (first query-args)]
    (for [[eid & _] (apply q query query-args)]
      (touch+ (d/entity the-db eid)))))

(defn touch-one
  "Runs a query that returns [[eid][eid]], and returns the first entity, touched"
  [query & query-args]
  (first (apply touch-all query query-args)))

(defn uuid []
  (UUID/randomUUID))

(defn generate-eids [conn tempid-count]
  ;; TODO: support for multiple parts
  (let [tempids (take tempid-count (repeatedly #(d/tempid :db.part/user)))
        transaction (d/transact conn (mapv (fn [tempid] {:db/id tempid :dummy :dummy/dummy}) tempids))]
    (mapv (fn [tempid] (d/resolve-tempid (:db-after @transaction) (:tempids @transaction) tempid)) tempids)))

;; should we convert a to its name (it's currently using its eid)?
;; Would require a reference to the db
(defn datom->transaction [datom]
  (let [{:keys [a e v tx added]} datom]
    [(if added :db/add :db/retract) e a v]))

(defn revert-transaction [conn {:keys [tx-data db-after] :as transaction-report}]
  (d/transact conn (map (fn [{:keys [a e v tx added] :as datom}]
                          [(if added :db/retract :db/add) e a v])
                        (remove #(->> % :a (d/entity db-after) :db/ident (= :db/txInstant))
                                tx-data))))

(defn rollback
  "Reassert retracted datoms and retract asserted datoms in a transaction,
  effectively \"undoing\" the transaction."
  [conn tx]
  (let [tx-log (-> conn d/log (d/tx-range tx nil) first) ; find the transaction
        txid   (-> tx-log :t d/t->tx) ; get the transaction entity id
        newdata (->> (:data tx-log)   ; get the datoms from the transaction
                     (remove #(= (:e %) txid)) ; remove transaction-metadata datoms
                     ; invert the datoms add/retract state.
                     (map #(do [(if (:added %) :db/retract :db/add) (:e %) (:a %) (:v %)]))
                     reverse)] ; reverse order of inverted datoms.
    @(d/transact conn newdata)))  ; commit new datoms.

;; TODO: This really needs a test
(defn unique-conflict? [ex]
  (loop [ex ex]
    (if (:db/error (ex-data ex))
      (= (:db/error (ex-data ex)) :db.error/unique-conflict)
      (if (.getCause ex)
        (recur (.getCause ex))
        false))))

(defonce tx-report-ch (async/chan (async/sliding-buffer 1024)))

(defn setup-tx-report-ch [conn]
  (let [queue (d/tx-report-queue conn)]
    (future (while true
              (let [transaction (.take queue)]
                (async/put! tx-report-ch transaction))))))

(defn init []
  (infof "Creating default database if it doesn't exist: %s"
         (d/create-database default-uri))
  (infof "Ensuring connection to default database")
  (infof "Connected to: %s" (conn))
  (infof "forwarding report-queue to tx-report-ch")
  (setup-tx-report-ch (conn)))
