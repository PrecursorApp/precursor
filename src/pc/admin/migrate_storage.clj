(ns pc.admin.migrate-storage
  (:require [datomic.api :refer [db q] :as d]
            [clojure.tools.logging :as log]
            [clojure.core.async :as async]
            [clj-http.client :as http]
            [clj-time.core :as time]
            [clj-time.coerce :refer [to-date]]
            [org.httpkit.server :as httpkit]
            [pc.auth]
            [pc.mixpanel :as mixpanel]
            [pc.datomic :as pcd]
            [pc.models.doc :as doc-model]
            [pc.models.layer :as layer-model]
            [slingshot.slingshot :refer (try+)]))

(defonce transaction-queue (atom []))
(defonce transaction-tap (async/chan (async/sliding-buffer 1024)))

(defn pop-transaction []
  (loop [val @transaction-queue]
    (if (compare-and-set! transaction-queue val (vec (rest val)))
      (first val)
      (recur @transaction-queue))))

(defn datoms->transaction [t]
  (let [datoms (:tx-data t)
        new-txid (d/tempid :db.part/tx)
        old-txid (-> datoms first :e)]
    (->> datoms
      (mapv pcd/datom->transaction)
      (mapv (fn [d] (update-in d [1] #(if (= % old-txid)
                                        new-txid
                                        %)))))))

(def random-uri "/F4WAmHkFNzEPZ9izhuikzT7hUOw")

(defn setup-transaction-server []
  (def server (httpkit/run-server (fn [req]
                                    (if (= (:uri req) random-uri)
                                      {:body (pr-str {:data (datoms->transaction (pop-transaction))})
                                       :status 200}))
                                  {:port 8067})))

(defn setup-transaction-queue []
  (async/tap (async/mult pcd/tx-report-ch) transaction-tap)
  (async/go-loop []
    (when-let [transaction (async/<! transaction-tap)]
      (swap! transaction-queue conj transaction)
      (recur))))

(defonce original-tempid (deref #'d/tempid))
(defonce original-resolve-tempid (deref #'d/resolve-tempid))

(defonce premade-ids (atom []))
(defonce used-premade-ids (atom #{}))

(defn pop-id []
  (loop [val @premade-ids]
    (if (compare-and-set! premade-ids val (rest val))
      (first val)
      (recur @premade-ids))))

(defn generate-premade-ids [id-count]
  (let [tempids (repeatedly id-count #(d/tempid :db.part/user))
        t (d/transact (pcd/conn) (mapv (fn [tempid]
                                         {:db/id tempid :pre-made :pre-made/free-to-postgres})
                                       tempids))]
    (mapv (fn [tempid] (d/resolve-tempid (:db-after @t) (:tempids @t) tempid)) tempids)))

(defn maybe-get-premade-id [part]
  (if (= :db.part/user part)
    (if-let [id (pop-id)]
      (do (swap! used-premade-ids conj id)
          id)
      (do (println "Out of premade ids!")
          (log/warn "Out of premade ids!")
          (original-tempid part)))
    (original-tempid part)))

(defn maybe-resolve-premade-id [db tempids tempid]
  (if (contains? @used-premade-ids tempid)
    tempid
    (original-resolve-tempid db tempids tempid)))

(defn startup-premade-ids []
  (reset! premade-ids (generate-premade-ids 5000))
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

(defn setup-old-server []
  (startup-premade-ids)
  (setup-transaction-queue)
  (setup-transaction-server))

(defonce queue-consumer-interrupt (atom nil))
(defonce popped-transactions (atom []))

(def transaction-url (str "http://localhost:8067" random-uri))
(defn fetch-transaction []
  (-> (http/get transaction-url)
    :body
    read-string
    :data))

(defn start-transaction-queue-consumer []
  (future (while (not @queue-consumer-interrupt)
            (loop [t (fetch-transaction)]
              (when t
                (swap! popped-transactions conj t)
                @(d/transact (pcd/conn) t)
                (recur (fetch-transaction))))
            (Thread/sleep 100))))

;; sequence of events:
;; on the old machine:
;; (setup-old-server)
;; on the new datomic storage instance:
;; run backup from old storage and restore into new storage

;; on the new-web instance, started unhealthy so that haproxy doesn't serve requests to it
;; Also, stop it from ensuring schema, don't want another transaction
;; (start-transaction-queue-consumer)
;; wait for queue to clear
;; make the old instance unhealthy
;; shut down the old instance
