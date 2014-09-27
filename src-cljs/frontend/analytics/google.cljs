(ns frontend.analytics.google
  (:require [frontend.utils :as utils :include-macros true]))

(defn push [args]
  (utils/swallow-errors
   (js/_gaq.push (clj->js args))))

(defn track-event [& args]
  (utils/swallow-errors (push (cons "_trackEvent" args))))

(defn track-pageview [& args]
  (utils/swallow-errors (push (cons "_trackPageview" args))))
