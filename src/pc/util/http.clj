(ns pc.util.http
  (:require [cemerick.url :as url]
            [pc.profile :as profile]))


(defn self-base-url []
  (let [ssl? (profile/force-ssl?)]
    (url/map->URL {:path "/"
                   :port (if ssl?
                           (profile/https-port)
                           (profile/http-port))
                   :protocol (if ssl? "https" "http")
                   :host (profile/hostname)})))
