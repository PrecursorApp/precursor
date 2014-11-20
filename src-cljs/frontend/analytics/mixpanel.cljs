(ns frontend.analytics.mixpanel
  (:require [cljs.core.async :as async :refer [>! <! put! alts! chan sliding-buffer close!]]
            [frontend.datetime :refer [unix-timestamp]]
            [frontend.utils :as utils :include-macros true]))

(defn track [event & [props]]
  (utils/swallow-errors (js/mixpanel.track event (clj->js (merge {:event_time (unix-timestamp)} props)))))

(defn track-pageview [path]
  (utils/swallow-errors (js/mixpanel.track_pageview path)))

(defn register-once [props]
  (utils/swallow-errors (js/mixpanel.register_once (clj->js props))))

(defn name-tag [email]
  (utils/swallow-errors (js/mixpanel.name_tag email)))

(defn identify [uuid]
  (utils/swallow-errors (js/mixpanel.identify uuid)))

(defn managed-track [event & [props]]
  (let [ch (chan)]
    (js/mixpanel.track event (clj->js props)
                       #(do (put! ch %) (close! ch)))
    ch))

(defn set-people-props [props]
  (utils/swallow-errors
   (utils/inspect (js/mixpanel.people.set (clj->js props)))))
