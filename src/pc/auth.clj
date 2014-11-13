(ns pc.auth
  (:require [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [org.httpkit.client :as http]
            [pc.auth.google :as google-auth]
            [pc.models.cust :as cust]
            [pc.datomic :as pcd])
  (:import java.util.UUID))

;; TODO: move this elsewhere
(defn ping-chat-with-new-user [email]
  (try
    (http/post "https://hooks.slack.com/services/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"
               {:form-params {"payload" (json/encode {:text (str "New user! " email)})}})
    (catch Exception e
      (.printStacktrace e)
      (log/error e))))

(defn cust-from-google-oauth-code [code session-uuid]
  {:post [(string? (:google-account/sub %))]} ;; should never break, but just in case...
  (let [user-info (google-auth/user-info-from-code code)]
    (if-let [cust (cust/find-by-google-sub (pcd/default-db) (:sub user-info))]
      (cust/update! cust (merge {:cust/email (:email user-info)
                                 :cust/verified-email (:email_verified user-info)}
                                (when-not (:cust/http-session-key cust)
                                  {:cust/http-session-key (UUID/randomUUID)})))
      (try
        (ping-chat-with-new-user (:email user-info))
        (cust/create! {:cust/email (:email user-info)
                       :cust/verified-email (:email_verified user-info)
                       :cust/http-session-key (UUID/randomUUID)
                       :google-account/sub (:sub user-info)
                       :cust/uuid (or session-uuid (UUID/randomUUID))})
        (catch Exception e
          (if (pcd/unique-conflict? e)
            (cust/find-by-google-sub (pcd/default-db) (:sub user-info))
            (throw e)))))))
