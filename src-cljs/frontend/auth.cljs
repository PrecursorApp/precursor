(ns frontend.auth
  (:require [cemerick.url :as url]
            [clojure.string :as str]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]))

(def google-client-id (aget js/window "Precursor" "google-client-id"))
(def subdomain (aget js/window "Precursor" "subdomain"))
(def hostname (aget js/window "Precursor" "hostname"))

(defn auth-url [& {:keys [redirect-path scopes redirect-query source]
                   :or {scopes ["openid" "email"]}}]
  (let [url (url/url (.. js/window -location -href))
        redirect-query (or redirect-query
                           (when (seq (:query url))
                             (url/map->query (:query url))))]
    (str (url/map->URL {:protocol (.getScheme utils/parsed-uri)
                        :port (.getPort utils/parsed-uri)
                        :host hostname
                        :path "/login"
                        :query (cond-> {:redirect-path (or redirect-path (:path url))}
                                 redirect-query (assoc :redirect-query redirect-query)
                                 source (assoc :source source)
                                 subdomain (merge {:redirect-subdomain subdomain
                                                   :redirect-csrf-token (utils/csrf-token)}))}))))

;; TODO: we should have more info about users and docs in the frontend db
(defn has-document-access? [state doc-id]
  (not= :none (get-in state (state/document-access-path doc-id))))

;; TODO: handle more cases
(defn owner? [db doc cust]
  (= (str (:document/creator doc)) (str (:cust/uuid cust))))
