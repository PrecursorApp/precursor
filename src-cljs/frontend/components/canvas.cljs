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
            [goog.style]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]])
  (:import [goog.ui IdGenerator]))

;; layers are always denominated in absolute coordinates
;; transforms are applied to handle panning and zooming

(defmulti svg-element (fn [selected-eids layer] (:layer/type layer)))

(defmethod svg-element :default
  [selected-eids layer]
  (print "No svg element for " layer))

(defn maybe-add-selected [svg-layer layer selected-eids]
  (if (contains? selected-eids (:db/id layer))
    (update-in svg-layer [:className] #(str % " selected"))
    svg-layer))

(defmethod svg-element :layer.type/rect
  [selected-eids layer]
  (-> (svg/layer->svg-rect layer)
      (maybe-add-selected layer selected-eids)
      (clj->js)
      (dom/rect)))

(defmethod svg-element :layer.type/text
  [selected-eids layer]
  (-> (svg/layer->svg-text layer)
      (maybe-add-selected layer selected-eids)
      (clj->js)
      (dom/text (:layer/text layer))))

(defmethod svg-element :layer.type/line
  [selected-eids layer]
  (dom/line (clj->js (merge
                      layer
                      (when (contains? selected-eids (:db/id layer))
                        {:className "selected"})
                      {:x1          (:layer/start-x layer)
                       :y1          (:layer/start-y layer)
                       :x2          (:layer/end-x layer)
                       :y2          (:layer/end-y layer)
                       :strokeWidth (:layer/stroke-width layer)}))))

(defmethod svg-element :layer.type/path
  [selected-eids layer]
  (dom/path
   (clj->js (merge layer
                   {:d (:layer/path layer)
                    :stroke (:layer/stroke layer "black")
                    :fill "none"
                    :strokeWidth (:layer/stroke-width layer)
                    :className (when (contains? selected-eids (:db/id layer)) "selected")}))))

(defmethod svg-element :layer.type/group
  [state selected-eids layer]
  (print "Nothing to do for groups, yet."))

(defn state->cursor [state]
  (case (get-in state state/current-tool-path)
    :text "text"
    :select "default"
    "crosshair"))

(defn svg-layers [{:keys [selected-eid]} owner]
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
               (mapv (partial svg-element selected-eids) layers))))))

(defn subscriber-cursor-icon [tool]
  (case (name tool)
    "pen" :crosshair
    "line" :crosshair
    "rect" :crosshair
    "circle" :crosshair

    "text" :ibeam

    "select" :tool-select))

(defn cursor [[id subscriber] owner]
  (reify
    om/IRender
    (render [_]
      (if (and (:tool subscriber)
               (:show-mouse? subscriber))
        (html (common/svg-icon (subscriber-cursor-icon (:tool subscriber))
                               {:svg-props {:height 16 :width 16
                                            :class "mouse-tool"
                                            :x (- (first (:mouse-position subscriber)) 8)
                                            :y (- (last (:mouse-position subscriber)) 8)}
                                :path-props {:stroke (:color subscriber)}}))
        (dom/circle #js {:cx 0 :cy 0 :r 0})))))

(defn cursors [{:keys [subscribers client-uuid]} owner]
  (reify
    om/IRender
    (render [_]
      (apply dom/g nil
             (om/build-all cursor (dissoc subscribers client-uuid) {:opts {:client-uuid client-uuid}})))))

(defn text-input [layer owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (.focus (om/get-node owner "input")))
    om/IDidUpdate
    (did-update [_ _ _]
      (.focus (om/get-node owner "input"))
      (om/set-state! owner :input-min-width (.-width (goog.style/getSize (om/get-node owner "input-width-tester")))))
    om/IInitState
    (init-state [_]
      {:input-min-width 0})
    om/IRender
    (render [_]
      (let [{:keys [cast!]} (om/get-shared owner)
            text-style {:font-size (:layer/font-size layer 24)
                        :font-family (:layer/font-family layer "Roboto")}]
        (dom/foreignObject #js {:width "100%"
                                :height "100%"
                                :x (:layer/current-x layer)
                                ;; TODO: defaults for each layer when we create them
                                :y (- (:layer/current-y layer) (:layer/font-size layer 24))}
                           (dom/form #js {:className "svg-text-form"
                                          :onSubmit (fn [e]
                                                      (cast! :text-layer-finished)
                                                      false)}
                                     ;; TODO: experiment with a contentEditable div
                                     (dom/input #js {:type "text"
                                                     :value (or (:layer/text layer) "")
                                                     ;; TODO: defaults for each layer when we create them
                                                     :style (clj->js (merge text-style
                                                                            {:width (+ 150 (om/get-state owner :input-min-width))}))
                                                     :ref "input"
                                                     :onChange #(cast! :text-layer-edited {:value (.. % -target -value)})})
                                     (dom/div #js {:style (clj->js (merge {:visibility "hidden"
                                                                           :position "fixed"
                                                                           :top "-100px"
                                                                           :left "0"
                                                                           :display "inline-block"}
                                                                          text-style))
                                                   :ref "input-width-tester"}
                                              (:layer/text layer))))))))

(defn subscriber-layers [{:keys [layers]} owner]
  (reify
    om/IRender
    (render [_]
      (if-not (seq layers)
        (dom/g nil nil)
        (apply dom/g nil (mapv (fn [l] (svg-element #{} (merge l {:strokeDasharray "5,5"
                                                                  :layer/fill "none"
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
      (let [{:keys [cast! handlers]} (om/get-shared owner)
            camera (:camera payload)]
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
                                     :width        (str (cameras/grid-width camera))
                                     :height       (str (cameras/grid-height camera))
                                     :patternUnits "userSpaceOnUse"}
                                (dom/path #js {:d           (str "M " (cameras/grid-width camera) " 0 L 0 0 0 " (cameras/grid-width camera))
                                               :fill        "none"
                                               :stroke      "gray"
                                               :strokeWidth "0.5"}))
                   (dom/pattern #js {:id               "grid"
                                     :width            (str (* 10 (cameras/grid-width camera)))
                                     :height           (str (* 10 (cameras/grid-height camera)))
                                     :patternUnits     "userSpaceOnUse"
                                     :patternTransform (str "translate(" (:x camera) "," (:y camera) ")")}
                                (dom/rect #js {:width  (str (* 10 (cameras/grid-width camera)))
                                               :height (str (* 10 (cameras/grid-height camera)))
                                               :fill   "url(#small-grid)"})
                                (dom/path #js {:d           (str "M " (str (* 10 (cameras/grid-width camera))) " 0 L 0 0 0 " (str (* 10 (cameras/grid-width camera))))
                                               :fill        "none"
                                               :stroke      "gray"
                                               :strokeWidth "1"})))
                 (when (cameras/show-grid? payload)
                   (dom/rect #js {:id     "background-grid"
                                  :width  "100%"
                                  :height "100%"
                                  :fill   "url(#grid)"}))

                 (dom/g
                  #js {:transform (cameras/->svg-transform camera)}
                  (om/build cursors (select-keys payload [:subscribers :client-uuid]))
                  (om/build svg-layers (select-keys payload [:selected-eid]))
                  (om/build subscriber-layers {:layers (reduce (fn [acc [id subscriber]]
                                                                 (if-let [layer (:layer subscriber)]
                                                                   (conj acc (assoc layer
                                                                               :layer/end-x (:layer/current-x layer)
                                                                               :layer/end-y (:layer/current-y layer)
                                                                               :stroke (apply str "#" (take 6 id))
                                                                               :layer/stroke (apply str "#" (take 6 id))))
                                                                   acc))
                                                               [] (:subscribers payload))})
                  (when (and (settings/drawing-in-progress? payload)
                             (= :layer.type/text (get-in payload [:drawing :layer :layer/type])))
                    (om/build text-input (get-in payload [:drawing :layer])))

                  (when-let [sel (cond
                                  (= :layer.type/text (get-in payload [:drawing :layer :layer/type])) nil
                                  (settings/selection-in-progress? payload) (settings/selection payload)
                                  (settings/drawing-in-progress? payload) (settings/drawing payload)
                                  :else nil)]
                    (dom/g #js {:className "layers"}
                           (let [sel (merge sel
                                            {:layer/end-x (:layer/current-x sel)
                                             :layer/end-y (:layer/current-y sel)}
                                            (when (not= :layer.type/text (:layer/type sel))
                                              {:strokeDasharray "5,5"
                                               :fill "gray"
                                               :fillOpacity "0.25"})
                                            (when (= :layer.type/group (:layer/type sel))
                                              {:layer/type :layer.type/rect}))]
                             (svg-element #{} sel))))))))))
