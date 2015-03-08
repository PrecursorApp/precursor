(ns pc.http.handlers.custom-domain
  (:require [cemerick.url :as url]
            [pc.datomic :as pcd]
            [pc.models.team :as team-model]
            [pc.profile :as profile]))

;; XXX: redirect for things on the protected list
(def blacklist #{"www" "admin"})

(defn parse-subdomain [req]
  (last (re-find (re-pattern (str "(.+)\\." (profile/hostname)))
                 (:server-name req))))

(defn handle-custom-domains [handler req]
  (let [subdomain (parse-subdomain req)]
    (if-not subdomain
      (handler req)
      (handler (assoc req
                      :subdomain subdomain
                      :team (team-model/find-by-subdomain (pcd/default-db) subdomain))))))

(defn wrap-custom-domains [handler]
  (fn [req]
    (handle-custom-domains handler req)))
