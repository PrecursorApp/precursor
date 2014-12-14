(ns frontend.auth
  (:require [cemerick.url :as url]
            [clojure.string :as str]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]))

(def google-client-id (aget js/window "Precursor" "google-client-id"))

(defn google-redirect-uri []
  (let [port (.getPort utils/parsed-uri)]
    (str (.getScheme utils/parsed-uri) "://" (.getDomain utils/parsed-uri) (when port (str ":" port)) "/auth/google")))

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

;; TODO: we should have more info about users and docs in the frontend db
(defn has-document-access? [state doc-id]
  (not= :none (get-in state (state/document-access-path doc-id))))
