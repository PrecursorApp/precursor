(ns pc.admin.db
  (:require [datomic.api :refer [db q] :as d]
            [clojure.tools.logging :as log]
            [clojure.core.async :as async]
            [clj-time.core :as time]
            [clj-time.coerce :refer [to-date]]
            [pc.auth]
            [pc.mixpanel :as mixpanel]
            [pc.datomic :as pcd]
            [pc.models.doc :as doc-model]
            [pc.models.layer :as layer-model]
            [slingshot.slingshot :refer (try+)]))

(defn interesting-doc-ids [{:keys [start-time end-time layer-threshold limit]
                            :or {start-time (time/minus (time/now) (time/days 1))
                                 end-time (time/now)
                                 layer-threshold 10
                                 limit 100}}]
  (let [db (pcd/default-db)
        index-range (d/index-range db :db/txInstant (to-date start-time) (to-date end-time))
        earliest-tx (some-> index-range first .tx)
        latest-tx (some-> index-range last .tx)
        doc-ids (when (and earliest-tx latest-tx)
                  (map first (d/q '{:find [?d] :in [$ ?earliest-tx ?latest-tx]
                                    :where [[?t :document/id ?d ?tx]
                                            [?t :layer/name]
                                            [(<= ?earliest-tx ?tx)]
                                            [(>= ?latest-tx ?tx)]]}
                                  db earliest-tx latest-tx)))]
    (take limit (filter (fn [doc-id]
                          (< layer-threshold (or (ffirst (d/q '{:find [(count ?t)] :in [$ ?d]
                                                                :where [[?t :document/id ?d]
                                                                        [?t :layer/name]]}
                                                              db doc-id))
                                                 0)))
                        doc-ids))))

(defn copy-document
  "Creates a copy of the document, without any of the document's history"
  [db doc]
  (let [layers (layer-model/find-by-document db doc)
        new-doc (doc-model/create-public-doc! {:document/name (str "Clone of " (:document/name doc))})]
    (d/transact (pcd/conn) (map (fn [l] (assoc l
                                          :db/id (d/tempid :db.part/user)
                                          :document/id (:db/id new-doc)))
                                layers))
    new-doc))

(defonce transaction-queue (atom (clojure.lang.PersistentQueue/EMPTY)))
(defonce transaction-tap (async/chan (async/sliding-buffer 1024)))

(defn setup-transaction-queue []
  (async/tap (async/mult pcd/tx-report-ch) transaction-tap)
  (async/go-loop []
    (when-let [transaction (async/<! transaction-tap)]
      (swap! transaction-queue conj transaction)
      (recur))))

(defonce original-tempid (deref #'d/tempid))
(defonce original-resolve-tempid (deref #'d/resolve-tempid))

(defonce pre-made-ids (atom []))
(defonce used-pre-made-ids (atom #{}))

(defn pop-id []
  (loop [val @pre-made-ids]
    (if (compare-and-set! pre-made-ids val (rest val))
      (first val)
      (recur @pre-made-ids))))

(defn generate-pre-made-ids [id-count]
  (let [tempids (repeatedly id-count #(d/tempid :db.part/user))
        t (d/transact (pcd/conn) (mapv (fn [tempid]
                                         {:db/id tempid :pre-made :pre-made/free-to-postgres})
                                       tempids))]
    (mapv (fn [tempid] (d/resolve-tempid (:db-after @t) (:tempids @t) tempid)) tempids)))

(defn maybe-get-premade-id [part]
  (if (= :db.part/user part)
    (if-let [id (pop-id)]
      (do (swap! used-pre-made-ids conj id)
          id)
      (do (println "Out of premade ids!")
          (log/warn "Out of premade ids!")
          (original-tempid part)))
    (original-tempid part)))

(defn maybe-resolve-premade-id [db tempids tempid]
  (if (contains? @used-pre-made-ids tempid)
    tempid
    (original-resolve-tempid db tempids tempid)))

(defn startup-pre-made-ids []
  (reset! pre-made-ids (generate-pre-made-ids 5000))
  (alter-var-root (var d/resolve-tempid) (fn [_] maybe-resolve-premade-id))
  (alter-var-root (var d/tempid) (fn [_] maybe-get-premade-id)))

(defn reset-tempid []
  (alter-var-root (var d/tempid) (fn [_] original-tempid)))

(defn reset-resolve-tempid []
  (alter-var-root (var d/resolve-tempid) (fn [_] original-resolve-tempid)))

(defn reset-vars []
  (reset-tempid)
  ;; give it twice the tx timeout for transactions to complete
  (Thread/sleep (* 1000 10 2))
  (reset-resolve-tempid))

(defn start-transaction-queue-consumer [])

;; sequence of events:
;; (startup-premade-ids)
;; (setup-transaction-queue)
;; run backup and restore
;; (start-transaction-queue-consumer)
;; wait for queue to clear
;; switch connections, set up datomic listener for new connection
