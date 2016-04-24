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
            [pc.http.admin.urls :as admin-urls]
            [pc.http.datomic.common :as datomic-common]
            [pc.http.sente :as sente]
            [pc.http.urls :as urls]
            [pc.models.chat :as chat-model]
            [pc.models.cust :as cust-model]
            [pc.models.team :as team-model]
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
    (sente/notify-team-transaction (:db-after transaction) frontend-tx)))

(defn notify-issue-subscribers [transaction]
  (when-let [frontend-tx (datomic-common/frontend-issue-transaction transaction)]
    (sente/notify-issue-transaction (:db-after transaction) frontend-tx)))

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

(defn cust-admin-link [cust]
  (format "<%s|%s>"
          (admin-urls/cust-info-from-cust cust)
          (:cust/email cust)))

(defn team-admin-link [team]
  (format "<%s|%s>"
          (admin-urls/team-info-from-team team)
          (:team/subdomain team)))

(defn handle-precursor-pings [transaction]
  (let [db (:db-after transaction)
        datoms (:tx-data transaction)
        document (delay (:transaction/document (datomic-common/get-annotations transaction)))
        chat-body-eid (d/entid db :chat/body)]
    (when-let [chat-datom (first (filter #(= chat-body-eid (:a %)) datoms))]
      (let [slack-url (profile/slack-customer-ping-url)
            cust (some->> chat-datom :e (#(d/datoms db :eavt % :cust/uuid)) first :v (cust-model/find-by-uuid db))
            username (:cust/email cust "ping-bot")
            icon_url (str (:google-account/avatar cust))]

        (cond
          (contains? (cust-model/admin-emails) (:cust/email cust))
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
      (let [slack-url (profile/slack-customer-ping-url)
            team (some->> team-datom :e (d/entity db))
            cust (:team/creator team)
            username (:cust/email cust "ping-bot")
            icon_url (str (:google-account/avatar cust))]

        (let [message (format "%s created the %s subdomain"
                              (cust-admin-link cust)
                              (team-admin-link team))]
          (http/post slack-url {:form-params {"payload" (json/encode {:text message :username username :icon_url icon_url})}}))))))

(defn admin-notify-trial-extensions [transaction]
  (let [db (:db-after transaction)
        datoms (:tx-data transaction)
        extension-count-eid (d/entid db :plan/extension-count)
        annotations (datomic-common/get-annotations transaction)]
    (when-let [extension-datom (first (filter #(= extension-count-eid (:a %)) datoms))]
      (let [slack-url (profile/slack-customer-ping-url)
            cust (cust-model/find-by-uuid db (:cust/uuid annotations))
            username (:cust/email cust "ping-bot")
            icon_url (str (:google-account/avatar cust))
            team (team-model/find-by-plan db {:db/id (:e extension-datom)})]
        (let [message (format "%s extended the trial for %s for the %s time"
                              (cust-admin-link cust)
                              (team-admin-link team)
                              (:v extension-datom))]
          (http/post slack-url {:form-params {"payload" (json/encode {:text message :username username :icon_url icon_url})}}))))))

(defn admin-notify-issues [transaction]
  (let [annotations (datomic-common/get-annotations transaction)]
    (when (:transaction/issue-tx? annotations)
      (let [db (:db-after transaction)
            datoms (:tx-data transaction)
            issue-author-eid (d/entid db :issue/author)
            comment-author-eid (d/entid db :comment/author)]
        (when-let [new-issue-datom (first (filter #(= issue-author-eid (:a %)) datoms))]
          (let [slack-url (profile/slack-customer-ping-url)
                issue (d/entity db (:e new-issue-datom))
                cust (:issue/author issue)
                username (:cust/email cust "ping-bot")
                icon_url (str (:google-account/avatar cust))]
            (let [message (format "Created a new issue <%s|%s>"
                                  (urls/from-issue issue)
                                  (:issue/title issue))]
              (http/post slack-url {:form-params {"payload" (json/encode {:text message
                                                                          :username username
                                                                          :icon_url icon_url})}}))))
        (when-let [new-comment-datom (first (filter #(= comment-author-eid (:a %)) datoms))]
          (let [slack-url (profile/slack-customer-ping-url)
                comment (d/entity db (:e new-comment-datom))
                issue (d/entity db (:e (first (d/datoms db :vaet (:db/id comment) :issue/comments))))
                cust (:comment/author comment)
                username (:cust/email cust "ping-bot")
                icon_url (str (:google-account/avatar cust))]
            (let [message (format "Created a new comment on <%s|%s>"
                                  (urls/from-issue issue)
                                  (:issue/title issue))]
              (http/post slack-url {:form-params {"payload" (json/encode {:text message
                                                                          :username username
                                                                          :icon_url icon_url})}}))))))))

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
    (admin-notify-trial-extensions transaction))
  (utils/with-report-exceptions
    (admin-notify-issues transaction)))

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
    (notify-issue-subscribers transaction))
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
