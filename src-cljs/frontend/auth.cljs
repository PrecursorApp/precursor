(ns frontend.auth
  (:require [cemerick.url :as url]
            [clojure.string :as str]
            [frontend.utils :as utils :include-macros true]))

(def google-client-id (aget js/window "Precursor" "google-client-id"))

(defn google-redirect-uri []
  (str (.getScheme utils/parsed-uri) "://" (.getDomain utils/parsed-uri) ":" (.getPort utils/parsed-uri) "/auth/google"))

(defn auth-url [& {:keys [redirect-path scopes]
                   :or {scopes ["openid" "email"]}}]
  (let [state (url/url-encode (js/JSON.stringify #js {:csrf-token (utils/csrf-token)
                                                      :redirect-path (or redirect-path
                                                                         (-> (.-location js/window) (.-href) url/url :path))}))]
    (str (url/map->URL {:protocol "https"
                        :host "accounts.google.com"
                        :path "/o/oauth2/auth"
                        :query {:client_id google-client-id
                                :response_type "code"
                                :access_type "online"
                                :scope (str/join " " scopes)
                                :redirect_uri (google-redirect-uri)
                                :state state}}))))
