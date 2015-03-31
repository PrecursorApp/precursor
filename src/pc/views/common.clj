(ns pc.views.common
  (:require [pc.assets]
            [pc.profile :refer (prod-assets?)]))

(defn cdn-base-url []
  (if (prod-assets?)
    pc.assets/cdn-base-url
    ""))

(defn cdn-path [path]
  (str (cdn-base-url) path))

(defn external-cdn-path [path]
  (str "https://precursor.storage.googleapis.com" path))
