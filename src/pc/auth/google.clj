(ns pc.auth.google
  (:require [pc.profile :as profile]
            [pc.util.base64 :as base64]
            [pc.util.jwt :as jwt]
            [cemerick.url :as url]
            [cheshire.core :as json]
            [clj-http.client :as http]))

(def dev-google-client-secret "r5t9LYCCJA3SaHnsDNg-dRsF")
(defn google-client-secret []
  (or (System/getenv "GOOGLE_CLIENT_SECRET") dev-google-client-secret))

(def dev-google-client-id "572837751423-3ei6f9eg2ml3r7oadufjns94b5c91hle.apps.googleusercontent.com")
(defn google-client-id []
  (or (System/getenv "GOOGLE_CLIENT_ID") dev-google-client-id))

(defn redirect-uri []
  (str (url/map->URL
        {:protocol (if (profile/force-ssl?) "https" "http")
         :host (profile/hostname)
         :port (if (profile/force-ssl?)
                 (profile/https-port)
                 (profile/http-port))
         :path "/auth/google"})))

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
