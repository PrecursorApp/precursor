(ns pc.http.datomic
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [clj-time.core :as time]
            [org.httpkit.client :as http]
            [pc.http.sente :as sente]
            [pc.datomic :as pcd]
            [pc.profile :as profile]
            [datomic.api :refer [db q] :as d])
  (:import java.util.UUID))

(defn entity-id-request [eid-count]
  (cond (not (number? eid-count))
        {:status 400 :body (pr-str {:error "count is required and should be a number"})}
        (< 100 eid-count)
        {:status 400 :body (pr-str {:error "You can only ask for 100 entity ids"})}
        :else
        {:status 200 :body (pr-str {:entity-ids (pcd/generate-eids (pcd/conn) eid-count)})}))

(defn public?
  "Only let the frontend access entities with the entity-ids we create for the frontend"
  [db datom]
  (->> datom :e (d/entity db) :dummy (= :dummy/dummy)))

(defn enum? [a]
  (contains? #{:layer/type :entity/type} a))

(defn datom-read-api [db datom]
  (let [{:keys [e a v tx added] :as d} datom
        a (:db/ident (d/entity db a))
        v (if (enum? a)
            (:db/ident (d/entity db v))
            v)]
    {:e e :a a :v v :tx tx :added added}))

(defn get-float-attrs [db]
  (set (map last (d/q '{:find [?t ?i]
                        :where [[?t :db/valueType :db.type/float]
                                [?t :db/ident ?i]]}
                      db))))

(defn get-uuid-attrs [db]
  (set (map last (d/q '{:find [?t ?i]
                        :where [[?t :db/valueType :db.type/uuid]
                                [?t :db/ident ?i]]}
                      db))))

(defn coerce-floats [float-attrs [type e a v :as transaction]]
  (if (contains? float-attrs a)
    [type e a (float v)]
    transaction))

(defn coerce-uuids [uuid-attrs [type e a v :as transaction]]
  (if (and (contains? uuid-attrs a) (string? v))
    [type e a (UUID/fromString v)]
    transaction))

(defn coerce-server-timestamp [server-timestamp [type e a v :as transaction]]
  (if (and (= :db/add type) (= :server/timestamp a))
    [type e a server-timestamp]
    transaction))

(defn transact!
  "Takes datoms from tx-data on the frontend and applies them to the backend. Expects datoms to be maps.
   Returns backend's version of the datoms."
  [datoms document-id session-uuid]
  (cond (empty? datoms)
        {:status 400 :body (pr-str {:error "datoms is required and should be non-empty"})}
        (< 1000 (count datoms))
        {:status 400 :body (pr-str {:error "You can only transact 1000 datoms at once"})}
        (not (number? document-id))
        {:status 400 :body (pr-str {:error "document-id is required and should be an entity id"})}
        :else
        {:status 200
         :body {:datoms (let [db (pcd/default-db)
                              conn (pcd/conn)
                              txid (d/tempid :db.part/tx)
                              float-attrs (get-float-attrs db)
                              uuid-attrs (get-uuid-attrs db)
                              server-timestamp (java.util.Date.)]
                          (->> datoms
                               (filter (partial public? db))
                               (map pcd/datom->transaction)
                               (map (partial coerce-floats float-attrs))
                               (map (partial coerce-uuids uuid-attrs))
                               (map (partial coerce-server-timestamp server-timestamp))
                               (concat [{:db/id txid :document/id document-id :session/uuid session-uuid}])
                               (d/transact conn)
                               deref
                               :tx-data
                               (filter (partial public? db))
                               (map (partial datom-read-api db))))}}))

(defn get-annotations [transaction]
  (let [txid (-> transaction :tx-data first :tx)]
    (->> txid (d/entity (:db-after transaction)) (#(select-keys % [:document/id :session/uuid])))))

(defn handle-precursor-pings [document-id datoms]
  (when-let [ping-datoms (seq (filter #(and (= :chat/body (:a %))
                                            (re-find #"@prcrsr" (:v %)))
                                      datoms))]
    (doseq [datom ping-datoms
            :let [message (format "<https://prcrsr.com/document/%s|%s>: %s"
                                  document-id document-id (:v datom))]]
      (http/post "https://hooks.slack.com/services/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"
                 {:form-params {"payload" (json/encode {:text message})}}))))

(defn notify-subscribers [transaction]
  (def myt transaction)
  (let [annotations (get-annotations transaction)]
    (when (:document/id annotations)
      (when-let [public-datoms (->> transaction
                                    :tx-data
                                    (filter (partial public? (:db-before transaction)))
                                    (map (partial datom-read-api (:db-after transaction)))
                                    seq)]
        (sente/notify-transaction (merge {:tx-data public-datoms}
                                         annotations))
        (when (profile/prod?)
          (handle-precursor-pings (:document/id annotations) public-datoms))))))

(defn init []
  (let [conn (pcd/conn)
        tap (async/chan (async/sliding-buffer 1024))]
    (async/tap (async/mult pcd/tx-report-ch) tap)
    (async/go-loop []
                   (when-let [transaction (async/<! tap)]
                     (try
                       (notify-subscribers transaction)
                       (catch Exception e
                         (log/error e)))
                     (recur)))))
