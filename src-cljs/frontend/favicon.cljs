(ns frontend.favicon
  (:refer-clojure :exclude [reset!])
  (:require [dommy.attrs :as attrs]
            [frontend.utils :as utils :include-macros true])
  (:require-macros [dommy.macros :refer [sel sel1]]))

(def favicon-query "link[rel='icon']")
;; increment version to break Chrome's aggressive caching
(def version "1")

(defn set-favicon! [path]
  (utils/swallow-errors
   (attrs/set-attr! (sel1 favicon-query) :href (str path "?v=" version))))

(defn set-unread! []
  (set-favicon! "/favicon-notification.ico"))

(defn set-normal! []
  (set-favicon! "/favicon.ico"))
