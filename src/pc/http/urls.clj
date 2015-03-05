(ns pc.http.urls
  (:require [cemerick.url :as url]
            [pc.profile :as profile]))

(defn make-url [path & {:keys [query subdomain]}]
  (str (url/map->URL (merge {:host (str (when subdomain
                                          (str subdomain "."))
                                        (profile/hostname))
                             :protocol (if (profile/force-ssl?)
                                         "https"
                                         "http")
                             :port (if (profile/force-ssl?)
                                     (profile/https-port)
                                     (profile/http-port))
                             :path path}
                            (when query
                              {:query query})))))

(defn root []
  (make-url "/"))

(defn doc [doc-id & {:keys [query]}]
  (make-url (str "/document/" doc-id) :query query))

(defn doc-svg [doc-id & {:keys [query]}]
  (make-url (str "/document/" doc-id ".svg") :query query))

(defn doc-png [doc-id & {:keys [query]}]
  (make-url (str "/document/" doc-id ".png") :query query))

(defn blog-url [slug]
  (make-url (str "/blog/" slug)))
