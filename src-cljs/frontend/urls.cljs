(ns frontend.urls
  (:require [cemerick.url :as url]
            [frontend.config :as config]))

(defn absolute-url [path]
  (str (url/map->URL {:protocol config/scheme
                      :port config/port
                      :host (str (when config/subdomain (str config/subdomain "."))
                                 config/hostname)
                      :path path})))

(defn absolute-doc-url [doc-id]
  (absolute-url (str "/document/" doc-id)))

(defn invoice-url [team-uuid invoice-id]
  (absolute-url (str "/team/" team-uuid "/plan/invoice/" invoice-id)))
