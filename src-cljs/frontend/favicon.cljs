(ns frontend.favicon
  (:refer-clojure :exclude [reset!])
  (:require [dommy.attrs :as attrs]
            [frontend.utils :as utils :include-macros true])
  (:require-macros [dommy.macros :refer [sel sel1]]))

(def favicon-query "link[rel='icon']")

(defn get-color []
  (last (re-find #"favicon-([^\.]+)\.ico" (attrs/attr (sel1 favicon-query) :href))))

(defn set-color! [color]
  (utils/swallow-errors
   (if (= color (get-color))
     (utils/mlog "Not setting favicon to same color")
     (attrs/set-attr! (sel1 favicon-query) :href (str "/favicon-" color ".ico?v=28")))))

(defn reset! []
  ;; This seemed clever at the time, undefined is the default dark blue
  (set-color! "undefined"))
