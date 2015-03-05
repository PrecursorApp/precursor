(ns pc.http.datomic
  (:require [cheshire.core :as json]
            [clj-time.core :as time]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [datomic.api :refer [db q] :as d]
            [org.httpkit.client :as http]
            [pc.datomic :as pcd]
            [pc.datomic.schema :as schema]
            [pc.datomic.web-peer :as web-peer]
            [pc.early-access]
            [pc.email :as email]
            [pc.http.sente :as sente]
            [pc.http.urls :as urls]
            [pc.models.chat :as chat-model]
            [pc.models.cust :as cust-model]
            [pc.profile :as profile]
            [pc.rollbar :as rollbar]
            [pc.utils :as utils]
            [slingshot.slingshot :refer (try+ throw+)])
  (:import java.util.UUID))

;; TODO: is the transaction guaranteed to be the first? Can there be multiple?
(defn get-annotations [transaction]
  (let [txid (-> transaction :tx-data first :tx)]
    (d/entity (:db-after transaction) txid)))

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
    :permission/grant-date

    :access-grant/document
    :access-grant/email
    :access-grant/grant-date

    :access-request/document
    :access-request/cust ;; translated
    :access-request/status
    :access-request/create-date
    :access-request/deny-date

    })

(defn translate-datom-dispatch-fn [db d] (:a d))

(defmulti translate-datom translate-datom-dispatch-fn)

(defmethod translate-datom :default [db d]
  d)

;; TODO: teach the frontend how to lookup name from cust/uuid
;;       this will break if something else is associating cust/uuids
(defmethod translate-datom :cust/uuid [db d]
  (if (:chat/body (d/entity db (:original-e d)))
    (assoc d
           :a :chat/cust-name
           :v (or (chat-model/find-chat-name db (:v d))
                  (subs (str (:v d)) 0 6)))
    d))

(defmethod translate-datom :permission/cust [db d]
  (update-in d [:v] #(:cust/email (d/entity db %))))

(defmethod translate-datom :access-request/cust [db d]
  (update-in d [:v] #(:cust/email (d/entity db %))))

(defn datom-read-api [db datom]
  (let [{:keys [e a v tx added] :as d} datom
        a (schema/get-ident a)
        v (if (contains? (schema/enums) a)
            (schema/get-ident v)
            v)
        ;; Temporary fix until we teach frontend how to lookup cust name
        original-e e
        e (web-peer/client-id db e)]
    (->> {:e e :a a :v v :tx tx :added added :original-e original-e}
      (translate-datom db)
      (#(dissoc % :original-e)))))

(defn whitelisted? [datom]
  (contains? outgoing-whitelist (:a datom)))

(defn notify-subscribers [transaction]
  (let [annotations (get-annotations transaction)]
    (when (and (:document/id annotations)
               (:transaction/broadcast annotations))
      (when-let [public-datoms (->> transaction
                                 :tx-data
                                 (filter #(:frontend/id (d/entity (:db-after transaction) (:e %))))
                                 (map (partial datom-read-api (:db-after transaction)))
                                 (filter whitelisted?)
                                 seq)]
        (sente/notify-transaction (merge {:tx-data public-datoms}
                                         annotations))))))

;; TODO: this should use a channel instead of a future
(defn send-emails [transaction]
  (let [annotations (delay (get-annotations transaction))]
    (doseq [datom (:tx-data transaction)]
      (when (and (= :needs-email (schema/get-ident (:a datom)))
                 (not (contains? #{:transaction.source/unmark-sent-email
                                   :transaction.source/mark-sent-email}
                                 (:transaction/source @annotations))))
        (log/infof "Queueing email for %s" (:e datom))
        (email/send-entity-email (:db-after transaction) (schema/get-ident (:v datom)) (:e datom))))))

(defn handle-precursor-pings [transaction]
  (let [db (:db-after transaction)
        datoms (:tx-data transaction)
        document-id (delay (:document/id (get-annotations transaction)))
        chat-body-eid (d/entid db :chat/body)]
    (when-let [chat-datom (first (filter #(= chat-body-eid (:a %)) datoms))]
      (let [slack-url (if (profile/prod?)
                        "https://hooks.slack.com/services/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"
                        "https://hooks.slack.com/services/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")
            cust (some->> chat-datom :e (#(d/datoms db :eavt % :cust/uuid)) first :v (cust-model/find-by-uuid db))
            username (:cust/email cust "ping-bot")
            icon_url (str (:google-account/avatar cust))]

        (cond
          (contains? cust-model/admin-emails (:cust/email cust))
          (log/info "Not sending ping for admin")

          (re-find #"(?i)@prcrsr|@danny|@daniel" (:v chat-datom))
          (let [message (format "<%s|%s>: %s"
                                (urls/doc @document-id) @document-id (:v chat-datom))]
            (http/post slack-url {:form-params {"payload" (json/encode {:text message :username username :icon_url icon_url})}}))

          (= 1 (count (get @sente/document-subs @document-id)))
          (let [message (format "<%s|%s> is typing messages to himself: \n %s"
                                (urls/doc @document-id) @document-id (:v chat-datom))]
            (http/post slack-url {:form-params {"payload" (json/encode {:text message :username username :icon_url icon_url})}}))
          :else nil)))))

(defn handle-admin [transaction]
  (utils/with-report-exceptions
    (send-emails transaction))
  (utils/with-report-exceptions
    (handle-precursor-pings transaction))
  (utils/with-report-exceptions
    (pc.early-access/handle-early-access-requests transaction)))

(defonce raised-full-channel-exception? (atom nil))
(defn forward-to-admin-ch [admin-ch transaction]
  (try+
   (async/put! admin-ch transaction)
   (catch AssertionError e
     (if (re-find #"MAX-QUEUE-SIZE" (.getMessage e))
       (when-not @raised-full-channel-exception?
         (reset! raised-full-channel-exception? true)
         (rollbar/report-exception (Exception. "Admin channel is full, messages are dropping!"))
         (throw+))
       (throw+)))))

(defn handle-transaction [admin-ch transaction]
  (def myt transaction)
  (utils/with-report-exceptions
    (notify-subscribers transaction))
  (utils/with-report-exceptions
    (forward-to-admin-ch admin-ch transaction)))

(defn init []
  (let [conn (pcd/conn)
        tap (async/chan (async/sliding-buffer 1024))
        ;; purposefully not using a windowed buffer, we want to catch errors
        ;; on the producer side so we can alert someone.
        admin-ch (async/chan)]
    (async/tap pcd/tx-report-mult tap)
    (async/go-loop []
      (when-let [transaction (async/<! tap)]
        (utils/with-report-exceptions
          (handle-transaction admin-ch transaction))
        (recur)))
    (dotimes [x 2]
      ;; 2 consumers to reduce chance of slow request blocking, since we don't care about ordering
      (async/go-loop []
        (when-let [transaction (async/<! admin-ch)]
          (utils/with-report-exceptions
            (handle-admin transaction))
          (recur))))))
