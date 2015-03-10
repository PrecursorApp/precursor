(ns pc.http.handlers.custom-domain
  (:require [cemerick.url :as url]
            [pc.datomic :as pcd]
            [pc.models.team :as team-model]
            [pc.profile :as profile]))

;; XXX: redirect for things on the protected list
(def blacklist #{"www" "admin"})

;; starts with a letter, only contains letters, numbers, and hyphens
;; must be more than 3 characters
(def subdomain-pattern "[a-zA-Z]{1}[a-zA-Z0-9\\-]{3,}")

(defn parse-subdomain [req]
  (last (re-find (re-pattern (format "(%s)\\.%s" subdomain-pattern (profile/hostname)))
                 (:server-name req))))

;; XXX: redirect for things that don't match the pattern and aren't (profile/hostname)
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
