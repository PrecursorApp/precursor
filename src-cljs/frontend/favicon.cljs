(ns frontend.favicon
  (:require [frontend.utils :as utils :include-macros true]))

(def favicon-query "link[rel='icon']")
;; increment version to break Chrome's aggressive caching
(def version "1")

(defn set-favicon! [path]
  (utils/swallow-errors
   (.setAttribute (js/document.querySelector favicon-query) "href" (str path "?v=" version))))

(defn set-unread! []
  (set-favicon! (utils/cdn-path "/favicon-notification.ico")))

(defn set-normal! []
  (set-favicon! (utils/cdn-path "/favicon.ico")))
