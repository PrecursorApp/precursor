(ns pc.http.admin.auth
  (:require [cemerick.url :as url]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clj-time.coerce]
            [clj-time.format]
            [pc.datomic.admin-db :as admin-db]
            [pc.models.admin :as admin-model]
            [pc.profile :as profile]
            [pc.util.base64 :as base64]
            [pc.util.jwt :as jwt]
            [slingshot.slingshot :refer (try+ throw+)])
  (:import java.util.UUID))

(def dev-google-client-secret "lmeZyXtnpsbRebwNifcDSIL3")
(defn google-client-secret []
  (or (System/getenv "ADMIN_GOOGLE_CLIENT_SECRET") dev-google-client-secret))

(def dev-google-client-id "345098262227-gfao8a8ufsslfp2gjc4fh68vnmmfbmqr.apps.googleusercontent.com")
(defn google-client-id []
  (or (System/getenv "ADMIN_GOOGLE_CLIENT_ID") dev-google-client-id))

(defn redirect-uri []
  (str (url/map->URL
        {:protocol (if (profile/force-ssl?) "https" "http")
         :host (profile/admin-hostname)
         :port (if (profile/force-ssl?)
                 (profile/admin-https-port)
                 (profile/admin-http-port))
         :path "/auth/google"})))

(defn auth-url [csrf-token]
  (url/map->URL {:protocol "https"
                 :host "accounts.google.com"
                 :path "/o/oauth2/auth"
                 :query {:client_id (google-client-id)
                         :response_type "code"
                         :access_type "online"
                         :scope "email openid"
                         :redirect_uri (redirect-uri)
                         :state (json/encode {:csrf-token csrf-token})}}))

(defn fetch-code-info [code]
  (-> (http/post "https://accounts.google.com/o/oauth2/token"
                 {:form-params {:code code
                                :client_id (google-client-id)
                                :client_secret (google-client-secret)
                                :redirect_uri (redirect-uri)
                                :grant_type "authorization_code"}})
      :body
      (json/decode true)))

(defn parse-id-token [id-token]
  (-> (jwt/decode id-token)
      :payload))

;; https://developers.google.com/accounts/docs/OAuth2Login
(defn user-info-from-code [code]
  (-> code
      fetch-code-info
      :id_token
      parse-id-token
      ;; :sub is google's unique identifier for the user
      (select-keys [:email :email_verified :sub])))

(defn admin-from-google-oauth-code [code ring-req]
  {:post [(string? (:google-account/sub %))]} ;; should never break, but just in case...
  (let [user-info (user-info-from-code code)
        db (admin-db/admin-db)]
    (if-let [admin (admin-model/find-by-google-sub db (:sub user-info))]
      (if (:admin/http-session-key admin)
        admin
        (admin-model/update-session-key! admin (UUID/randomUUID)))
      (throw+ {:status 401 :public-message "Couldn't log you in, sorry :("}))))


(defn wrap-auth [handler]
  (fn [req]
    (let [db (admin-db/admin-db)
          admin (some->> req :session :admin-http-session-key (admin-model/find-by-http-session-key db))]
      (handler (cond-> req
                 admin (assoc-in [:auth :admin] admin))))))

(defn authorized? [req]
  (seq (get-in req [:auth :admin :admin/email])))
