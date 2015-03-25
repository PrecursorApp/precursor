(ns frontend.urls
  (:require [cemerick.url :as url]
            [frontend.config :as config]))

(defn absolute-doc-url [doc-id]
  (str (url/map->URL {:protocol config/scheme
                      :port config/port
                      :host config/hostname
                      :path (str "/document/" doc-id)})))
