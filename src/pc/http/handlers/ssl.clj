(ns pc.http.handlers.ssl
  (:require [cemerick.url :as url]
            [clojure.tools.logging :as log]
            [pc.profile :as profile]
            [slingshot.slingshot :refer (try+ throw+)]))

(defn ssl? [req]
  (or (= :https (:scheme req))
      (= "https" (get-in req [:headers "x-forwarded-proto"]))
      (seq (get-in req [:headers "x-haproxy-server-state"]))))

(defn wrap-force-ssl [handler {:keys [host https-port force-ssl?]
                               :or {host (profile/hostname) https-port (profile/https-port)
                                    force-ssl? (profile/force-ssl?)}}]
  (fn [req]
    (if (or (not force-ssl?)
            (ssl? req))
      (handler req)
      {:status 301
       :headers {"Location" (str (url/map->URL {:host host
                                                :protocol "https"
                                                :port https-port
                                                :path (:uri req)
                                                :query (:query-string req)}))}
       :body ""})))
