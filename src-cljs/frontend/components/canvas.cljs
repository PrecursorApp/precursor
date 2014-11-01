(ns frontend.components.canvas
  (:require [datascript :as d]
            [cljs.core.async :refer [put!]]
            [frontend.camera :as cameras]
            [frontend.components.common :as common]
            [frontend.datascript :as ds]
            [frontend.layers :as layers]
            [frontend.models.layer :as layer-model]
            [frontend.settings :as settings]
            [frontend.state :as state]
            [frontend.svg :as svg]
            [frontend.utils :as utils :include-macros true]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]])
  (:import [goog.ui IdGenerator]))

;; layers are always denominated in absolute coordinates
;; transforms are applied to handle panning and zooming

(defmulti svg-element (fn [state selected-eids layer] (:layer/type layer)))

(defmethod svg-element :default
  [camera selected-eids layer]
  (print "No svg element for " layer))

(defn maybe-add-selected [svg-layer layer selected-eids]
  (if (contains? selected-eids (:db/id layer))
    (update-in svg-layer [:className] #(str % " selected"))
    svg-layer))

(defmethod svg-element :layer.type/rect
  [camera selected-eids layer]
  (-> (svg/layer->svg-rect camera layer)
      (maybe-add-selected layer selected-eids)
      (clj->js)
      (dom/rect)))

(defmethod svg-element :layer.type/text
  [camera selected-eids layer]
  (-> (svg/layer->svg-rect camera layer)
      (merge {:fontFamily (:layer/font-family layer)
              :fontSize   (* (:layer/font-size layer)
                             (:zf camera))})
      (maybe-add-selected layer selected-eids)
      (clj->js)
      (dom/text (:layer/text layer))))

(defmethod svg-element :layer.type/line
  [camera selected-eids layer]
  (dom/line (clj->js (merge
                      layer
                      (when (contains? selected-eids (:db/id layer))
                        {:className "selected"})
                      {:x1          (:layer/start-x layer)
                       :y1          (:layer/start-y layer)
                       :x2          (:layer/end-x layer)
                       :y2          (:layer/end-y layer)
                       :strokeWidth (:layer/stroke-width layer)
                       :transform (cameras/->svg-transform camera)}))))

(defmethod svg-element :layer.type/path
  [camera selected-eids layer]
  (dom/path
   (clj->js (merge layer
                   {:d (:layer/path layer)
                    :stroke (:layer/stroke layer "black")
                    :fill "none"
                    :strokeWidth (:layer/stroke-width layer)
                    :transform (cameras/->svg-transform camera)
                    :className (when (contains? selected-eids (:db/id layer)) "selected")}))))

(defmethod svg-element :layer.type/group
  [state selected-eids layer]
  (print "Nothing to do for groups, yet."))

(defn state->cursor [state]
  (case (get-in state state/current-tool-path)
    :text "text"
    :select "default"
    "crosshair"))

(defn svg-layers [{:keys [selected-eid camera]} owner]
  (reify
    om/IInitState (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (d/listen! (om/get-shared owner :db)
                 (om/get-state owner :listener-key)
                 (fn [tx-report]
                   ;; TODO: better way to check if state changed
                   (when (seq (:tx-data tx-report))
                     (om/refresh! owner)))))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared (om/get-shared owner :db)) (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)
            selected-eids (if selected-eid (layer-model/selected-eids @db selected-eid) #{})
            layers (ds/touch-all '[:find ?t :where [?t :layer/name]] @db)]
        (apply dom/g #js {:className "layers"}
               (mapv (partial svg-element camera selected-eids) layers))))))

(defn cursor [[id subscriber] owner]
  (reify
    om/IRender
    (render [_]
      (if (and (:tool subscriber)
               (:show-mouse? subscriber))
        (html (common/svg-icon (keyword (str "tool-" (name (:tool subscriber))))
                               {:svg-props {:height 16 :width 16
                                            :class "mouse-tool"
                                            :x (first (:mouse-position subscriber))
                                            :y (last (:mouse-position subscriber))}
                                :path-props {:stroke (apply str "#" (take 6 id))}}))
        (dom/circle #js {:cx 0 :cy 0 :r 0})))))

(defn cursors [{:keys [subscribers client-uuid]} owner]
  (reify
    om/IRender
    (render [_]
      (apply dom/g nil
             (om/build-all cursor (dissoc subscribers client-uuid) {:opts {:client-uuid client-uuid}})))))

(defn subscriber-layers [{:keys [layers camera]} owner]
  (reify
    om/IRender
    (render [_]
      (if-not (seq layers)
        (dom/g nil nil)
        (apply dom/g nil (mapv (fn [l] (svg-element camera #{} (merge (utils/inspect l) {:strokeDasharray "5,5"
                                                                            :fillOpacity "0.25"})))
                               layers))))))

(defn svg-canvas [payload owner opts]
  (reify
    om/IDidMount
    (did-mount [_]
      (let [{:keys [cast!]} (om/get-shared owner)
            node (om/get-node owner)
            [x y] [(.-offsetLeft node)
                   (.-offsetTop node)]]
        (cast! :canvas-mounted [x y])))
    om/IRender
    (render [_]
      (let [{:keys [cast! handlers]} (om/get-shared owner)]
        (dom/svg #js {:width "100%"
                      :height "100%"
                      :id "svg-canvas"
                      :xmlns "http://www.w3.org/2000/svg"
                      :style #js {:top    0
                                  :left   0
                                  :cursor (state->cursor payload)}
                      :onTouchStart (fn [event]
                                      (let [touches (.-touches event)]
                                        (when (= (.-length touches) 1)
                                          (.preventDefault event)
                                          ;; This was keeping app-main's touch event from working
                                          ;; (.stopPropagation event)
                                          (js/console.log event)
                                          (om/set-state! owner :touch-timer (js/setTimeout #(cast! :menu-opened) 500))
                                          ((:handle-mouse-down handlers) (aget touches "0")))))
                      :onTouchEnd (fn [event]
                                    (.preventDefault event)
                                        ; (.stopPropagation event)
                                    (js/console.log event)
                                    (js/clearInterval (om/get-state owner :touch-timer))
                                    ((:handle-mouse-up handlers) event))
                      :onTouchMove (fn [event]
                                     (let [touches (.-touches event)]
                                       (when (= (.-length touches) 1)
                                         (.preventDefault event)
                                         (.stopPropagation event)
                                         (js/console.log event)
                                         (js/clearInterval (om/get-state owner :touch-timer))
                                         ((:handle-mouse-move! handlers) (aget touches "0")))))
                      :onMouseDown (fn [event]
                                     (.preventDefault event)
                                     (.stopPropagation event)
                                     ;;(.addEventListener js/document "mousemove" handle-mouse-move false)
                                     (js/console.log event)
                                     ((:handle-mouse-down handlers) event))
                      :onMouseUp (fn [event]
                                   (.preventDefault event)
                                   (.stopPropagation event)
                                   ;;(.removeEventListener js/document "mousemove" handle-mouse-move false)
                                   ((:handle-mouse-up handlers) event))
                      :onWheel (fn [event]
                                 (.preventDefault event)
                                 (.stopPropagation event)
                                 (let [dx     (- (aget event "deltaX"))
                                       dy     (if (aget event "nativeEvent" "webkitDirectionInvertedFromDevice")
                                                ;; Detect inverted scroll (natural scroll)
                                                (aget event "deltaY")
                                                (- (aget event "deltaY")))]
                                   (om/transact! payload (fn [state]
                                                           (let [camera (cameras/camera state)
                                                                 mode   (cameras/camera-mouse-mode state)]
                                                             (if (= mode :zoom)
                                                               (cameras/set-zoom state (partial + (* -0.002 dy)))
                                                               (cameras/move-camera state dx dy)))))))}
                 (dom/defs nil
                   (dom/pattern #js {:id           "small-grid"
                                     :width        (str (cameras/grid-width payload))
                                     :height       (str (cameras/grid-height payload))
                                     :patternUnits "userSpaceOnUse"}
                                (dom/path #js {:d           (str "M " (cameras/grid-width payload) " 0 L 0 0 0 " (cameras/grid-width payload))
                                               :fill        "none"
                                               :stroke      "gray"
                                               :strokeWidth "0.5"}))
                   (dom/pattern #js {:id               "grid"
                                     :width            (str (* 10 (cameras/grid-width payload)))
                                     :height           (str (* 10 (cameras/grid-height payload)))
                                     :patternUnits     "userSpaceOnUse"
                                     :patternTransform (str "translate(" (:x (cameras/camera payload)) "," (:y (cameras/camera payload)) ")")}
                                (dom/rect #js {:width  (str (* 10 (cameras/grid-width payload)))
                                               :height (str (* 10 (cameras/grid-height payload)))
                                               :fill   "url(#small-grid)"})
                                (dom/path #js {:d           (str "M " (str (* 10 (cameras/grid-width payload))) " 0 L 0 0 0 " (str (* 10 (cameras/grid-width payload))))
                                               :fill        "none"
                                               :stroke      "gray"
                                               :strokeWidth "1"})))
                 (when (cameras/show-grid? payload)
                   (dom/rect #js {:id     "background-grid"
                                  :width  "100%"
                                  :height "100%"
                                  :fill   "url(#grid)"}))
                 (om/build cursors (select-keys payload [:subscribers :client-uuid]))
                 (om/build svg-layers (select-keys payload [:camera :selected-eid]))
                 (dom/text #js {:x (get-in payload [:mouse :x])
                                :y (get-in payload [:mouse :y])
                                :className "mouse-stats"}
                           (pr-str (:mouse payload)))
                 (om/build subscriber-layers {:layers (reduce (fn [acc [id subscriber]]
                                                                (if-let [layer (:layer subscriber)]
                                                                  (conj acc (assoc layer
                                                                              :layer/end-x (:layer/current-x layer)
                                                                              :layer/end-y (:layer/current-y layer)
                                                                              :stroke (apply str "#" (take 6 id))
                                                                              :layer/stroke (apply str "#" (take 6 id))))
                                                                  acc))
                                                              [] (:subscribers payload))
                                              :camera (:camers payload)})
                 (when-let [sel (cond
                                 (settings/selection-in-progress? payload) (settings/selection payload)
                                 (settings/drawing-in-progress? payload) (settings/drawing payload)
                                 :else nil)]
                   (dom/g #js {:className "layers"}
                          (let [sel (merge sel
                                           {:layer/end-x (:layer/current-x sel)
                                            :layer/end-y (:layer/current-y sel)
                                            :strokeDasharray "5,5"
                                            :fill "gray"
                                            :fillOpacity "0.25"}
                                           (when (= :layer.type/group (:layer/type sel))
                                             {:layer/type :layer.type/rect}))]
                            (svg-element (:camera payload) #{} sel)))))))))
