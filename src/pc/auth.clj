(ns pc.auth
  (:require [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [org.httpkit.client :as http]
            [pc.analytics :as analytics]
            [pc.auth.google :as google-auth]
            [pc.models.cust :as cust]
            [pc.datomic :as pcd]
            [pc.profile :as profile]
            [pc.utils :as utils])
  (:import java.util.UUID))

;; TODO: move this elsewhere
(defn ping-chat-with-new-user [email]
  (try
    (let [db (pcd/default-db)
          message (str "New user (#" (cust/cust-count db) "): " email)]
      (http/post "https://hooks.slack.com/services/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"
                 ;; Note: counting this way is racy!
                 {:form-params {"payload" (json/encode {:text message})}}))
    (catch Exception e
      (.printStacktrace e)
      (log/error e))))

(defn update-user-from-sub [cust]
  (let [sub (:google-account/sub cust)
        {:keys [first-name last-name
                birthday gender occupation]} (google-auth/user-info-from-sub sub)
        cust (cust/update! cust (utils/remove-map-nils {:cust/first-name first-name
                                                        :cust/last-name last-name
                                                        :cust/birthday birthday
                                                        :cust/gender gender
                                                        :cust/occupation occupation}))]
    (analytics/track-user-info cust)
    cust))

(defn cust-from-google-oauth-code [code ring-req]
  {:post [(string? (:google-account/sub %))]} ;; should never break, but just in case...
  (let [user-info (google-auth/user-info-from-code code)]
    (if-let [cust (cust/find-by-google-sub (pcd/default-db) (:sub user-info))]
      (do
        (analytics/track-login cust)
        (cust/update! cust (merge {:cust/email (:email user-info)
                                   :cust/verified-email (:email_verified user-info)}
                                  (when-not (:cust/http-session-key cust)
                                    {:cust/http-session-key (UUID/randomUUID)}))))
      (try
        (let [user (cust/create! {:cust/email (:email user-info)
                                  :cust/verified-email (:email_verified user-info)
                                  :cust/http-session-key (UUID/randomUUID)
                                  :google-account/sub (:sub user-info)
                                  :cust/uuid (UUID/randomUUID)})]
          (when (profile/prod?)
            (ping-chat-with-new-user (:email user-info)))
          (analytics/track-signup user ring-req)
          (future (update-user-from-sub user))
          user)
        (catch Exception e
          (if (pcd/unique-conflict? e)
            (cust/find-by-google-sub (pcd/default-db) (:sub user-info))
            (throw e)))))))
