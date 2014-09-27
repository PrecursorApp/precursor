(ns frontend.analytics.twitter
  (:require [frontend.utils :as utils :include-macros true]
            [goog.net.jsloader]))

(defn track-pid [pid]
  (utils/swallow-errors
   (js/twttr.conversion.trackPid pid)))

(defn track-conversion
  "Twitter has a separate pid for each type of conversion. Loads
   twitter if necessary and sends the conversion."
  [pid]
  (if (aget js/window "twttr")
    (track-pid pid)
    (-> (goog.net.jsloader.load "//platform.twitter.com/oct.js")
        (.addCallback #(track-pid pid)))))

(defn track-signup []
  (utils/swallow-errors
   (track-conversion "l4lg6")))

(defn track-payer []
  (utils/swallow-errors
   (track-conversion "l4m9v")))
