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

(defn issue-url [issue]
  (str "/issues/" (:frontend/issue-id issue)))

(defn doc-path [doc-id & {:keys [query-params]}]
  (str "/document/" doc-id (when (seq query-params)
                             (str "?" (url/map->query query-params)))))

(defn overlay-path [doc-id overlay & {:keys [query-params]}]
  (str "/document/" doc-id "/" overlay (when (seq query-params)
                                         (str "?" (url/map->query query-params)))))

(defn plan-submenu-path [doc-id submenu & {:keys [query-params]}]
  (str "/document/" doc-id "/plan/" submenu (when (seq query-params)
                                              (str "?" (url/map->query query-params)))))
