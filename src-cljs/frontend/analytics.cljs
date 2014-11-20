(ns frontend.analytics
  (:require [frontend.analytics.mixpanel :as mixpanel]
            [frontend.utils :as utils :include-macros true]))


(defn init-user [cust]
  (utils/swallow-errors
   (mixpanel/identify (:uuid cust))
   (mixpanel/name-tag (:email cust))
   (mixpanel/set-people-props {:$email (:email cust)
                               :$last_login (js/Date.)})))

(defn track-path [path]
  (mixpanel/track-pageview path))

(defn track-page [page & [props]]
  (mixpanel/track page props))

(defn track [page & [props]]
  (mixpanel/track page props))

(def controls-blacklist #{:chat-db-updated
                          :mouse-moved
                          :chat-body-changed
                          :default
                          :mouse-depressed
                          :mouse-released
                          :application-shutdown
                          :visibility-changed
                          :key-state-changed
                          :camera-nudged-right
                          :camera-nudged-left
                          :camera-nudged-down
                          :camera-nudged-up})

(defn track-control [event]
  (when-not (contains? controls-blacklist event)
    (mixpanel/track (str event))))
