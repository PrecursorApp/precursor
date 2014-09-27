(ns pc.stefon
  (:require [stefon.core :as stefon]))

(def stefon-options
  {:asset-roots ["resources/assets"]
   :mode :development})

(defn asset-path [file]
  (stefon/link-to-asset file stefon-options))
