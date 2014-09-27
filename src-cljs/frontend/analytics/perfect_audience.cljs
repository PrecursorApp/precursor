(ns frontend.analytics.perfect-audience
  (:require [frontend.utils :as utils :include-macros true]))

(defn track [event & props]
  (utils/swallow-errors
   (let [pq (or js/window._pq #js [])]
     (.push pq (clj->js (concat ["track" event] props))))))
