(ns pc.http.datomic
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [clj-time.core :as time]
            [org.httpkit.client :as http]
            [pc.http.datomic-common :as common]
            [pc.http.sente :as sente]
            [pc.datomic :as pcd]
            [pc.datomic.schema :as schema]
            [pc.email :as email]
            [pc.models.chat :as chat-model]
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

;; TODO: is the transaction guaranteed to be the first? Can there be multiple?
(defn get-annotations [transaction]
  (let [txid (-> transaction :tx-data first :tx)]
    (d/entity (:db-after transaction) txid)))

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

(def outgoing-whitelist
  #{:layer/name
    :layer/uuid
    :layer/type
    :layer/start-x
    :layer/start-y
    :layer/end-x
    :layer/end-y
    :layer/rx
    :layer/ry
    :layer/fill
    :layer/stroke-width
    :layer/stroke-color
    :layer/opacity

    :entity/type

    :layer/start-sx
    :layer/start-sy

    :layer/font-family
    :layer/text
    :layer/font-size
    :layer/path
    :layer/child
    :layer/ui-id
    :layer/ui-target
    :session/uuid
    :document/id ;; TODO: for layers use layer/document
    :document/uuid
    :document/name
    :document/creator
    :document/collaborators
    :document/privacy
    :chat/body
    :chat/color
    :chat/cust-name
    :cust/uuid
    :client/timestamp
    :server/timestamp

    :permission/document
    :permission/cust ;; translated
    :permission/permits

    :access-grant/document
    :access-grant/email})

;; TODO: teach the frontend how to lookup name from cust/uuid
;;       this will break if something else is associating cust/uuids
(defn maybe-replace-cust-uuid [db {:keys [a] :as d}]
  (if (= a :cust/uuid)
    (assoc d
      :a :chat/cust-name
      :v (chat-model/find-chat-name db (:v d)))
    d))

(defn translate-datom-dispatch-fn [db d] (:a d))

(defmulti translate-datom translate-datom-dispatch-fn)

(defmethod translate-datom :default [db d]
  d)

(defmethod translate-datom :cust/uuid [db d]
  (if (:chat/body (d/entity db (:e d)))
    (assoc d
           :a :chat/cust-name
           :v (chat-model/find-chat-name db (:v d)))
    d))

(defmethod translate-datom :cust/uuid [db d]
  (if (:chat/body (d/entity db (:e d)))
    (assoc d
           :a :chat/cust-name
           :v (chat-model/find-chat-name db (:v d)))
    d))

(defmethod translate-datom :permission/cust [db d]
  (update-in d [:v] #(:cust/email (d/entity db %))))

(defn datom-read-api [db datom]
  (let [{:keys [e a v tx added] :as d} datom
        a (schema/get-ident a)
        v (if (contains? (schema/enums) a)
            (schema/get-ident v)
            v)]
    (->> {:e e :a a :v v :tx tx :added added}
      (translate-datom db))))

(defn whitelisted? [datom]
  (contains? outgoing-whitelist (:a datom)))

(defn notify-subscribers [transaction]
  (let [annotations (get-annotations transaction)]
    (when (and (:document/id annotations)
               (:transaction/broadcast annotations))
      (when-let [public-datoms (->> transaction
                                 :tx-data
                                 (map (partial datom-read-api (:db-after transaction)))
                                 (filter whitelisted?)
                                 seq)]
        (sente/notify-transaction (merge {:tx-data public-datoms}
                                         annotations))
        (when (profile/prod?)
          (handle-precursor-pings (:document/id annotations) public-datoms))))))

(defn send-emails [transaction]
  (doseq [datom (:tx-data transaction)]
    (when (= :email/access-grant-created (schema/get-ident (:v datom)))
      (log/infof "Sending access grant email for %s" (:e datom))
      (future (email/send-access-grant-email (:db-after transaction) (:e datom))))))

(defn handle-transaction [transaction]
  (def myt transaction)
  (notify-subscribers transaction)
  (send-emails transaction))

(defn init []
  (let [conn (pcd/conn)
        tap (async/chan (async/sliding-buffer 1024))]
    (async/tap (async/mult pcd/tx-report-ch) tap)
    (async/go-loop []
                   (when-let [transaction (async/<! tap)]
                     (try
                       (handle-transaction transaction)
                       (catch Exception e
                         (.printStacktrace e)
                         (log/error e)))
                     (recur)))))
