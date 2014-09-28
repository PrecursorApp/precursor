(ns frontend.controllers.controls
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [cljs.reader :as reader]
            [frontend.async :refer [put!]]
            [frontend.components.forms :refer [release-button!]]
            [datascript :as d]
            [frontend.camera :as cameras]
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

(defmethod control-event :canvas-mounted
  [target message [x y] state]
  (-> state
      (update-in [:camera] assoc :offset-x x :offset-y y)))

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

(defmethod control-event :drawing-started
  [target message _ state]
  (let [{:keys [x y]} (get-in state [:mouse])
        [rx ry]       (cameras/screen->point (:camera state) x y)
        entity-id     (-> state :entity-ids first)
        layer         (layers/make-layer entity-id (:document/id state) rx ry)]
    (let [r (-> state
                (assoc-in [:drawing :in-progress?] true)
                (assoc-in [:drawing :layer] layer)
                (update-in [:drawing :layer] assoc
                           :layer/start-sx (- x (get-in state [:camera :offset-x]))
                           :layer/start-sy (- y (get-in state [:camera :offset-y]))
                           :layer/current-sx (- x (get-in state [:camera :offset-x]))
                           :layer/current-sy (- y (get-in state [:camera :offset-y])))
                (assoc-in [:mouse :down] true)
                (assoc-in [:mouse :x] x)
                (assoc-in [:mouse :y] y)
                (assoc-in [:mouse :rx] rx)
                (assoc-in [:mouse :ry] ry)
                (update-in [:entity-ids] disj entity-id))]
            r)))

(defmethod control-event :mouse-moved
  [target message [x y] state]
  (let [[rx ry] (cameras/screen->point (:camera state) x y)]
    (let [r (if (get-in state [:drawing :in-progress?])
              (-> state
                  (update-in [:mouse] assoc :x x :y y :rx rx :ry ry)
                  (update-in [:drawing :layer] assoc
                             :layer/current-x rx
                             :layer/current-y ry
                             :layer/current-sx (- x (get-in state [:camera :offset-x]))
                             :layer/current-sy (- y (get-in state [:camera :offset-y]))))
              (-> state
                  (update-in [:mouse] assoc :x x :y y :rx rx :ry ry)))]
      r)))

(defmethod control-event :mouse-released
  [target message [x y] state]
  (let [[rx ry] (cameras/screen->point (:camera state) x y)]
    (-> state
        (update-in [:drawing :layer] dissoc :layer/current-x :layer/current-y)
        (update-in [:drawing :layer] assoc :layer/end-x rx :layer/end-y ry)
        (update-in [:drawing] assoc :in-progress? false)
        (assoc-in [:mouse :down] false)
        (assoc-in [:mouse :x] x)
        (assoc-in [:mouse :y] y)
        (assoc-in [:mouse :rx] rx)
        (assoc-in [:mouse :ry] ry)
        (update-in [:drawing :layer] assoc
                   :layer/end-x rx
                   :layer/end-y ry
                   :layer/current-x nil
                   :layer/current-y nil
                   :layer/start-sx nil
                   :layer/start-sy nil
                   :layer/current-sx nil
                   :layer/current-sy nil)
        (assoc-in [:camera :moving?] false))))

(defmethod post-control-event! :mouse-depressed
  [target message [x y] previous-state current-state]
  (let [cast! (fn [msg & [payload]]
                (put! (get-in current-state [:comms :controls]) [msg payload]))]
    (cond
     (get-in current-state [:keyboard :meta?])         (cast! :menu-opened)
     (= (get-in current-state [:current-tool]) :text)  (let [text (js/prompt "Layer text:")]
                                                         (cast! :text-layer-created [text]))
     (= (get-in current-state [:current-tool]) :shape) (cast! :drawing-started)
     (= (get-in current-state [:current-tool]) :line)  (cast! :drawing-started)
     :else                                             nil)))

(defmethod post-control-event! :mouse-released
  [target message [x y] previous-state current-state]
  (let [cast! #(put! (get-in current-state [:comms :controls]) [%])
        db           (:db current-state)
        was-drawing? (get-in previous-state [:drawing :in-progress?])
        layer        (get-in current-state [:drawing :layer])]
    (cond
     (get-in current-state [:menu :open?]) (cast! :menu-closed)
     was-drawing? (d/transact! db [layer])
     :else nil)))


(defmethod control-event :menu-opened
  [target message _ state]
  (print "menu opened")
  (-> state
      (update-in [:menu] assoc
                 :open? true
                 :x (get-in state [:mouse :x])
                 :y (get-in state [:mouse :y]))))

(defmethod control-event :menu-closed
  [target message _ state]
  (-> state
      (assoc-in [:menu :open?] false)))

(defmethod control-event :tool-selected
  [target message [tool] state]
  (-> state
      (assoc-in [:current-tool] tool)))

(defmethod control-event :text-layer-created
  [target message [text] state]
  (let [{:keys [rx ry]} (:mouse state)
        entity-id       (-> state :entity-ids first)
        layer           (assoc (layers/make-layer entity-id (:document/id state) rx ry)
                          :layer/type :layer.type/text
                          :layer/font-family "Roboto"
                          :layer/font-size 24
                          :layer/stroke-width 0
                          
                          :layer/text text)]
    (-> state
        (assoc-in [:drawing :layer] layer)
        (update-in [:entity-ids] disj entity-id))))

(defmethod post-control-event! :text-layer-created
  [target message [x y] previous-state current-state]
  (let [db    (:db current-state)
        layer (get-in current-state [:drawing :layer])]
    (d/transact! db [layer])))

(defmethod post-control-event! :mouse-released
  [target message [x y] previous-state current-state]
  (let [cast! #(put! (get-in current-state [:comms :controls]) [%])
        db           (:db current-state)
        was-drawing? (get-in previous-state [:drawing :in-progress?])
        layer        (get-in current-state [:drawing :layer])]
    (cond
     (get-in current-state [:menu :open?]) (cast! :menu-closed)
     was-drawing? (d/transact! db [layer])
     :else nil)))

(defmethod control-event :db-updated
  [target message _ state]
  (assoc state :random-number (Math/random)))
