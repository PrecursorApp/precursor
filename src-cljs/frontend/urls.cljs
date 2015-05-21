(ns frontend.urls
  (:require [cemerick.url :as url]
            [clojure.string :as str]
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

(defn urlify-doc-name
  "Strips html characters and replaces everything else with dashes"
  [doc-name]
  (-> (or doc-name "")
    (str/replace #"[&<>\"']" "")
    (str/replace #"[^A-Za-z0-9-_]+" "-")
    (str/replace #"^-" "")
    (str/replace #"-$" "")))

(defn name-segment [doc]
  (let [url-safe-name (urlify-doc-name (:document/name doc))]
    (str (when (seq url-safe-name)
           (str url-safe-name "-"))
         (:db/id doc))))

(defn absolute-doc-url [doc & {:as args}]
  (utils/apply-map absolute-url (str "/document/" (name-segment doc)) args))

(defn absolute-doc-svg [doc & {:as args}]
  (utils/apply-map absolute-url (str "/document/" (name-segment doc) ".svg") args))

(defn absolute-doc-png [doc & {:as args}]
  (utils/apply-map absolute-url (str "/document/" (name-segment doc) ".png") args))

(defn absolute-doc-pdf [doc & {:as args}]
  (utils/apply-map absolute-url (str "/document/" (name-segment doc) ".pdf") args))

(defn invoice-url [team-uuid invoice-id & {:as args}]
  (utils/apply-map absolute-url (str "/team/" team-uuid "/plan/invoice/" invoice-id) args))

(defn issue-url [issue]
  (str "/issues/" (:frontend/issue-id issue)))

(defn doc-path* [doc]
  (str "/document/" (name-segment doc)))

(defn doc-path [doc & {:keys [query-params]}]
  (str (doc-path* doc) (when (seq query-params)
                         (str "?" (url/map->query query-params)))))

(defn doc-svg-path [doc & {:keys [query-params]}]
  (str (doc-path* doc) ".svg " (when (seq query-params)
                                 (str "?" (url/map->query query-params)))))

(defn doc-png-path [doc & {:keys [query-params]}]
  (str (doc-path* doc) ".png " (when (seq query-params)
                                 (str "?" (url/map->query query-params)))))

(defn doc-pdf-path [doc & {:keys [query-params]}]
  (str (doc-path* doc) ".pdf " (when (seq query-params)
                                 (str "?" (url/map->query query-params)))))

(defn overlay-path [doc overlay & {:keys [query-params]}]
  (str (doc-path* doc) "/" overlay (when (seq query-params)
                                     (str "?" (url/map->query query-params)))))

(defn plan-submenu-path [doc submenu & {:keys [query-params]}]
  (str (doc-path* doc) "/plan/" submenu (when (seq query-params)
                                          (str "?" (url/map->query query-params)))))
