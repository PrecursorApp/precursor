(ns frontend.urls
  (:require [cemerick.url :as url]
            [frontend.config :as config]
            [frontend.utils :as utils]))

(defn absolute-url [path & {:keys [subdomain query]
                            :or {subdomain config/subdomain}}]
  (str (url/map->URL (merge {:protocol config/scheme
                             :port config/port
                             :host (str (when subdomain (str subdomain "."))
                                        config/hostname)
                             :path path}
                            (when query
                              {:query query})))))

(defn absolute-doc-url [doc-id & {:as args}]
  (utils/apply-map absolute-url (str "/document/" doc-id) args))

(defn absolute-doc-svg [doc-id & {:as args}]
  (utils/apply-map absolute-url (str "/document/" doc-id ".svg") args))

(defn absolute-doc-png [doc-id & {:as args}]
  (utils/apply-map absolute-url (str "/document/" doc-id ".png") args))

(defn absolute-doc-pdf [doc-id & {:as args}]
  (utils/apply-map absolute-url (str "/document/" doc-id ".pdf") args))

(defn invoice-url [team-uuid invoice-id & {:as args}]
  (utils/apply-map absolute-url (str "/team/" team-uuid "/plan/invoice/" invoice-id) args))
