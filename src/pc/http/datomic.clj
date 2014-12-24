(ns pc.http.datomic
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [clj-time.core :as time]
            [org.httpkit.client :as http]
            [pc.http.datomic-common :as common]
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

(defn get-annotations [transaction]
  (let [txid (-> transaction :tx-data first :tx)]
    (->> txid (d/entity (:db-after transaction)) (#(select-keys % [:document/id :session/uuid :cust/uuid])))))

(defn handle-precursor-pings [document-id datoms]
  (if-let [ping-datoms (seq (filter #(and (= :chat/body (:a %))
                                            (re-find #"(?i)@prcrsr|@danny|@daniel" (:v %)))
                                      datoms))]
    (doseq [datom ping-datoms
            :let [message (format "<https://prcrsr.com/document/%s|%s>: %s"
                                  document-id document-id (:v datom))]]
      (http/post "https://hooks.slack.com/services/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"
                 {:form-params {"payload" (json/encode {:text message})}}))
    (when (and (first (filter #(= :chat/body (:a %)) datoms))
               (= 1 (count (get @sente/document-subs document-id))))
      (let [message (format "<https://prcrsr.com/document/%s|%s> is typing messages to himself: \n %s"
                            document-id document-id (:v (first (filter #(= :chat/body (:a %)) datoms))))]
        (http/post "https://hooks.slack.com/services/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"
                   {:form-params {"payload" (json/encode {:text message})}})))))

(defn notify-subscribers [transaction]
  (def myt transaction)
  (let [annotations (get-annotations transaction)]
    (when (and (:document/id annotations)
               (:transaction/broadcast annotations))
      (when-let [public-datoms (->> transaction
                                 :tx-data
                                 (filter (fn [d] (common/public? (:db-before transaction) (:e d))))
                                 (map (partial common/datom-read-api (:db-after transaction)))
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
                         (.printStacktrace e)
                         (log/error e)))
                     (recur)))))
