(ns pc.http.admin.urls
  (:require [cemerick.url :as url]
            [pc.profile :as profile]))

(defn make-url [path & {:keys [query]}]
  (str (url/map->URL (merge {:host (profile/admin-hostname)
                             :protocol (if (profile/force-ssl?)
                                         "https"
                                         "http")
                             :port (if (profile/force-ssl?)
                                     (profile/admin-https-port)
                                     (profile/admin-http-port))
                             :path path}
                            (when query
                              {:query query})))))

(defn cust-info-from-cust [cust]
  (make-url (str "/user/" (:cust/email cust))))

(defn team-info-from-team [team]
  (make-url (str "/team/" (:team/subdomain team))))
