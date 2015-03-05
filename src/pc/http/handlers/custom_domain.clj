(ns pc.http.handlers.custom-domain
  (:require [cemerick.url :as url]
            [pc.http.urls :as urls]
            [pc.profile :as profile]
            [ring.middleware.anti-forgery]))

(def blacklist #{"www" "admin"})

(defn parse-subdomain [req]
  (last (re-find (re-pattern (str "(.+)\\." (profile/hostname)))
                 (:server-name req))))

(defn wrap-custom-domains [handler]
  (fn [req]
    (if-let [subdomain (parse-subdomain req)]
      (cond
        (get-in req [:auth :cust])
        {:status 200
         :body (str "Hi " (get-in req [:auth :cust :cust/email]) "!")}

        (= "/auth/google" (:uri req))
        (handler req)

        :else
        {:status 200
         :body (format "<body><a href='%s'>Log in with Google</a></body>"
                       (urls/make-url "/login" :query (url/map->query {:redirect-subdomain subdomain
                                                                       :redirect-csrf-token ring.middleware.anti-forgery/*anti-forgery-token*})))})
      (handler req))))
