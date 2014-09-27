(ns frontend.analytics.mixpanel
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [frontend.async :refer [put!]]
            [frontend.utils :as utils :include-macros true]))

(defn track [event & [props]]
  (utils/swallow-errors (js/mixpanel.track event (clj->js props))))

(defn track-pageview [path]
  (utils/swallow-errors (js/mixpanel.track_pageview path)))

(defn register-once [props]
  (utils/swallow-errors (js/mixpanel.register_once (clj->js props))))

(defn name-tag [username]
  (utils/swallow-errors (js/mixpanel.name_tag username)))

(defn identify [username]
  (utils/swallow-errors (js/mixpanel.identify username)))

(defn managed-track [event & [props]]
  (let [ch (chan)]
    (js/mixpanel.track event (clj->js props)
                       #(do (put! ch %) (close! ch)))
    ch))

(defn init-user [login]
  (name-tag login)
  (identify login))


(def ignored-control-messages #{:edited-input :toggled-input :clear-inputs})

(defn track-message [message]
  (when-not (contains? ignored-control-messages message)
    (track (name message))))

(defn set-existing-user []
  (utils/swallow-errors
   (js/CI.ExistingUserHeuristics.)))
