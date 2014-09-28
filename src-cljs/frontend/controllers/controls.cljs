(ns frontend.controllers.controls
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [cljs.reader :as reader]
            [frontend.async :refer [put!]]
            [frontend.components.forms :refer [release-button!]]
            [datascript :as d]
            [frontend.datascript :as ds]
            [frontend.layers :as layers]
            [frontend.routes :as routes]
            [frontend.state :as state]
            [frontend.stripe :as stripe]
            [frontend.utils.ajax :as ajax]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.seq :refer [dissoc-in]]
            [frontend.utils.state :as state-utils]
            [goog.string :as gstring]
            [goog.labs.userAgent.engine :as engine]
            goog.style)
  (:require-macros [dommy.macros :refer [sel sel1]]
                   [cljs.core.async.macros :as am :refer [go go-loop alt!]])
  (:import [goog.fx.dom.Scroll]))

;; --- Navigation Multimethod Declarations ---

(defmulti control-event
  ;; target is the DOM node at the top level for the app
  ;; message is the dispatch method (1st arg in the channel vector)
  ;; state is current state of the app
  ;; return value is the new state
  (fn [target message args state] message))

(defmulti post-control-event!
  (fn [target message args previous-state current-state] message))

;; --- Navigation Multimethod Implementations ---

(defmethod control-event :default
  [target message args state]
  (utils/mlog "Unknown controls: " message)
  state)

(defmethod post-control-event! :default
  [target message args previous-state current-state]
  (utils/mlog "No post-control for: " message))

(defmethod control-event :state-restored
  [target message path state]
  (let [str-data (.getItem js/sessionStorage "circle-state")]
    (if (seq str-data)
      (-> str-data
          reader/read-string
          (assoc :comms (:comms state)))
      state)))

(defmethod post-control-event! :state-persisted
  [target message channel-id previous-state current-state]
  (.setItem js/sessionStorage "circle-state"
            (pr-str (dissoc current-state :comms))))

(defmethod control-event :camera-nudged-up
  [target message _ state]
  (update-in state [:camera :y] inc))

(defmethod control-event :camera-nudged-down
  [target message _ state]
  (update-in state [:camera :y] dec))

(defmethod control-event :camera-nudged-left
  [target message _ state]
  (update-in state [:camera :x] inc))

(defmethod control-event :camera-nudged-right
  [target message _ state]
  (update-in state [:camera :x] dec))

(defmethod control-event :key-state-changed
  [target message [{:keys [key-name-kw depressed?]}] state]
  (assoc-in state [:keyboard key-name-kw] depressed?))

(defmethod control-event :show-grid-toggled
  [target message {:keys [project-id]} state]
  (update-in state state/show-grid-path not))

(defmethod control-event :night-mode-toggled
  [target message {:keys [project-id]} state]
  (update-in state state/night-mode-path not))

(defmethod control-event :mouse-depressed
  [target message [x y] state]
  (let [layer (layers/make-layer x y)]
    (-> state
        (assoc-in [:drawing :in-progress?] true)
        (assoc-in [:drawing :layer] layer))))

(defmethod control-event :mouse-moved
  [target message [x y] state]
  (if (get-in state [:drawing :in-progress?])
    (-> state
        (update-in [:drawing :layer] assoc
                   :layer/current-x x
                   :layer/current-y y))
    state))

(defmethod control-event :mouse-released
  [target message [x y] state]
  (-> state
      (update-in [:drawing :layer] dissoc :layer/current-x :layer/current-y)
      (update-in [:drawing :layer] assoc :layer/end-x x :layer/end-y y)
      (update-in [:drawing] assoc :in-progress? false)))

(defmethod post-control-event! :mouse-depressed
  [target message [x y] previous-state current-state])

(defmethod post-control-event! :mouse-moved
  [target message [x y] previous-state current-state])

(defmethod post-control-event! :mouse-released
  [target message [x y] previous-state current-state]
  (let [db (:db current-state)
        was-drawing? (get-in previous-state [:drawing :in-progress?])
        layer (get-in current-state [:drawing :layer])]
    (when was-drawing?
      (d/transact! db [layer])
      (print "New db: " db))))
