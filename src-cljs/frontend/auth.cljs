(ns frontend.auth
  (:require [cemerick.url :as url]
            [clojure.string :as str]
            [frontend.config :as config]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]))

(def google-client-id (aget js/window "Precursor" "google-client-id"))

(defn auth-url [& {:keys [redirect-path scopes redirect-query source subdomain login-hint]
                   :or {scopes ["openid" "email"]}}]
  (let [url (url/url (.. js/window -location -href))
        redirect-query (or redirect-query
                           (when (seq (:query url))
                             (url/map->query (:query url))))]
    (str (url/map->URL {:protocol config/scheme
                        :port config/port
                        :host (if subdomain
                                (str subdomain "." config/hostname)
                                config/hostname)
                        :path "/login"
                        :query (cond-> {:redirect-path (or redirect-path (:path url))}
                                 redirect-query
                                 (assoc :redirect-query redirect-query)

                                 source
                                 (assoc :source source)

                                 config/subdomain
                                 (merge {:redirect-subdomain config/subdomain
                                         :redirect-csrf-token (utils/csrf-token)})

                                 subdomain
                                 (merge {:redirect-subdomain subdomain
                                         :redirect-csrf-token (utils/csrf-token)})

                                 login-hint
                                 (assoc :login-hint login-hint))}))))

;; TODO: we should have more info about users and docs in the frontend db
(defn has-document-access? [state doc-id]
  (not= :none (get-in state (state/document-access-path doc-id))))

;; TODO: this needs to be in the db (along with doc access)
(defn has-team-permission? [state team-uuid]
  (not= :none (get-in state (state/team-access-path team-uuid))))

;; TODO: handle more cases
(defn owner? [db doc cust]
  (= (str (:document/creator doc)) (str (:cust/uuid cust))))

(def scope-heirarchy [:read :admin :owner])
(def scope-index {:read 0 :admin 1 :owner 2})

(defn contains-scope? [heirarchy granted-scope requested-scope]
  (contains? (set (take (inc (get scope-index granted-scope)) heirarchy))
             requested-scope))
