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
            [pc.http.datomic.common :as datomic-common]
            [pc.http.sente :as sente]
            [pc.http.urls :as urls]
            [pc.models.chat :as chat-model]
            [pc.models.cust :as cust-model]
            [pc.profile :as profile]
            [pc.rollbar :as rollbar]
            [pc.utils :as utils]
            [slingshot.slingshot :refer (try+ throw+)])
  (:import java.util.UUID))

(defn notify-document-subscribers [transaction]
  (when-let [frontend-tx (datomic-common/frontend-document-transaction transaction)]
    (sente/notify-document-transaction (:db-after transaction) frontend-tx)))

(defn notify-team-subscribers [transaction]
  (when-let [frontend-tx (datomic-common/frontend-team-transaction transaction)]
    (sente/notify-document-transaction (:db-after transaction) frontend-tx)))

;; TODO: this should use a channel instead of a future
(defn send-emails [transaction]
  (let [annotations (delay (datomic-common/get-annotations transaction))]
    (doseq [datom (:tx-data transaction)]
      (when (and (:added datom)
                 (= :needs-email (schema/get-ident (:a datom)))
                 (not (contains? #{:transaction.source/unmark-sent-email
                                   :transaction.source/mark-sent-email}
                                 (:transaction/source @annotations))))
        (log/infof "Queueing email for %s" (:e datom))
        (email/send-entity-email (:db-after transaction) (schema/get-ident (:v datom)) (:e datom))))))

(defn handle-precursor-pings [transaction]
  (let [db (:db-after transaction)
        datoms (:tx-data transaction)
        document (delay (:transaction/document (datomic-common/get-annotations transaction)))
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
                                (urls/from-doc @document) (:db/id @document) (:v chat-datom))]
            (http/post slack-url {:form-params {"payload" (json/encode {:text message :username username :icon_url icon_url
                                                                        :attachments [{:image_url (urls/png-from-doc @document)}]})}}))

          (= 1 (count (get @sente/document-subs (:db/id @document))))
          (let [message (format "<%s|%s> is typing messages to himself: \n %s"
                                (urls/from-doc @document) (:db/id @document) (:v chat-datom))]
            (http/post slack-url {:form-params {"payload" (json/encode {:text message :username username :icon_url icon_url
                                                                        :attachments [{:image_url (urls/png-from-doc @document)}]})}}))
          :else nil)))))

(defn admin-notify-subdomains [transaction]
  (let [db (:db-after transaction)
        datoms (:tx-data transaction)
        team-eid (d/entid db :team/subdomain)]
    (when-let [team-datom (first (filter #(= team-eid (:a %)) datoms))]
      (let [slack-url (if (profile/prod?)
                        "https://hooks.slack.com/services/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"
                        "https://hooks.slack.com/services/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")
            team (some->> team-datom :e (d/entity db))
            cust (:team/creator team)
            username (:cust/email cust "ping-bot")
            icon_url (str (:google-account/avatar cust))]

        (let [message (format "created the %s subdomain" (:team/subdomain team))]
          (http/post slack-url {:form-params {"payload" (json/encode {:text message :username username :icon_url icon_url})}}))))))

(defn admin-notify-solo-trials [transaction]
  (let [db (:db-after transaction)
        datoms (:tx-data transaction)
        private-docs-eid (d/entid db :flags/private-docs)]
    (when-let [flag-datom (first (filter #(= private-docs-eid (:v %)) datoms))]
      (let [slack-url (if (profile/prod?)
                        "https://hooks.slack.com/services/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"
                        "https://hooks.slack.com/services/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")
            cust (d/entity db (:e flag-datom))
            username (:cust/email cust "ping-bot")
            icon_url (str (:google-account/avatar cust))]
        (let [message "started a solo trial"]
          (http/post slack-url {:form-params {"payload" (json/encode {:text message :username username :icon_url icon_url})}}))))))

(defn handle-admin [transaction]
  (utils/with-report-exceptions
    (send-emails transaction))
  (utils/with-report-exceptions
    (handle-precursor-pings transaction))
  (utils/with-report-exceptions
    (pc.early-access/handle-early-access-requests transaction))
  (utils/with-report-exceptions
    (admin-notify-subdomains transaction))
  (utils/with-report-exceptions
    (admin-notify-solo-trials transaction)))

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
    (notify-document-subscribers transaction))
  (utils/with-report-exceptions
    (notify-team-subscribers transaction))
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
