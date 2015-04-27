(ns frontend.urls
  (:require [cemerick.url :as url]
            [frontend.config :as config]
            [frontend.utils :as utils]))

(defn absolute-url [path & {:keys [subdomain]
                            :or {subdomain config/subdomain}}]
  (str (url/map->URL {:protocol config/scheme
                      :port config/port
                      :host (str (when subdomain (str subdomain "."))
                                 config/hostname)
                      :path path})))

(defn absolute-doc-url [doc-id & {:as args}]
  (utils/apply-map absolute-url (str "/document/" doc-id) args))

(defn invoice-url [team-uuid invoice-id & {:as args}]
  (utils/apply-map absolute-url (str "/team/" team-uuid "/plan/invoice/" invoice-id) args))
