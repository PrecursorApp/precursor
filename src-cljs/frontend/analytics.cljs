(ns frontend.analytics
  (:require [frontend.analytics.mixpanel :as mixpanel]
            [frontend.analytics.rollbar :as rollbar]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]))

(defn init-user [cust]
  (utils/swallow-errors
   (rollbar/init (str (:cust/uuid cust)) (:cust/email cust)))
  (utils/swallow-errors
   (mixpanel/identify (str (:cust/uuid cust)))
   (mixpanel/name-tag (:cust/email cust))
   (mixpanel/set-people-props {:$email (:cust/email cust)
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
                          :camera-nudged-up
                          :text-layer-edited
                          :layer-ui-id-edited
                          :layer-ui-target-edited
                          :subscriber-updated
                          :handle-camera-query-params
                          :media-stream-volume})

(defn track-control [event data state]
  (when-not (contains? controls-blacklist event)
    (mixpanel/track (str event) (merge
                                 (:analytics-data data)
                                 (dissoc (get-in state state/browser-settings-path) :document-settings)
                                 {:logged-in? (boolean (get-in state [:cust :cust/email]))}
                                 (when (:document/id state)
                                   {:doc-id (:document/id state)
                                    :subscriber-count (count (get-in state [:subscribers :info]))})))))
