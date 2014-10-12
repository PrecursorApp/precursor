(ns frontend.components.canvas
  (:require [frontend.camera :as cameras]
            [frontend.datascript :as ds]
            [frontend.settings :as settings]
            [frontend.svg :as svg]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]]))

(defmulti svg-element (fn [state cast! layer] (:layer/type layer)))

(defmethod svg-element :default
  [state cast! layer]
  (print "No svg element for " layer))

(defmethod svg-element :layer.type/rect
  [state cast! layer]
  (dom/rect (clj->js (svg/layer->svg-rect (cameras/camera state) layer true cast!))))

(defmethod svg-element :layer.type/text
  [state cast! layer]
  (dom/text (clj->js (merge (svg/layer->svg-rect (cameras/camera state) layer true cast!)
                            {:fontFamily (:layer/font-family layer)
                             :fontSize   (* (:layer/font-size layer)
                                            (:zf (:camera state)))})) (:layer/text layer)))

(defmethod svg-element :layer.type/line
  [state cast! layer]
  (let [l (cameras/camera-translated-rect (:camera state) layer (- (:layer/end-x layer) (:layer/start-x layer))
                                          (- (:layer/end-y layer) (:layer/start-y layer)))]
    (dom/line (clj->js (merge
                        (dissoc l :x :y :width :height :stroke-width :fill)
                        {:x1          (:layer/start-x l)
                         :y1          (:layer/start-y l)
                         :x2          (:layer/end-x l)
                         :y2          (:layer/end-y l)
                         :strokeWidth (:layer/stroke-width l)
                         ;;:stroke      (:layer/fill l)
                         }))
              (:layer/text layer))))

(defmethod svg-element :layer.type/group
  [state cast! layer]
  (print "Nothing to do for groups, yet."))

(defn state->cursor [state]
  (case (:current-tool state)
    :text "text"
    :select "default"
    "crosshair"))

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
            db                       (om/get-shared owner :db)
            layers                   (ds/touch-all '[:find ?t :where [?t :layer/name]] @db)]
        (apply dom/svg (concat [#js {:width "100%"
                                     :height "100%"
                                     :id "svg-canvas"
                                     :xmlns "http://www.w3.org/2000/svg"
                                     :style #js {:top    0
                                                 :left   0
                                                 :cursor (state->cursor payload)}
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
                                                                              (cameras/move-camera state dx dy)))))))}]
                               [(dom/defs nil
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
                                                 :fill   "url(#grid)"}))]
                               (mapv (partial svg-element payload cast!) layers)
                               [(dom/text #js {:x (get-in payload [:mouse :x])
                                               :y (get-in payload [:mouse :y])
                                               :fill "green"}
                                          (pr-str (select-keys payload [:mouse :current-tool])))]
                               [(when-let [sel (cond
                                                (settings/selection-in-progress? payload) (settings/selection payload)
                                                (settings/drawing-in-progress? payload) (settings/drawing payload)
                                                :else nil)]
                                  (let [sel (merge sel
                                                   {:layer/start-x   (get-in sel [:layer/start-sx])
                                                    :layer/start-y   (get-in sel [:layer/start-sy])
                                                    :layer/current-x (or (get-in sel [:layer/current-sx])
                                                                         (get-in sel [:layer/end-sx]))
                                                    :layer/current-y (or (get-in sel [:layer/current-sy])
                                                                         (get-in sel [:layer/end-sy]))})
                                        el-type (cond
                                                 (= (:current-tool payload) :line) dom/line
                                                 :else dom/dom)]
                                    (dom/rect
                                     (clj->js (assoc (svg/layer->svg-rect (:camera payload) sel
                                                                          false
                                                                          cast!)
                                                :fill "gray"
                                                :fillOpacity "0.25"
                                                :strokeDasharray "5,5"
                                                :strokeWidth 1)))))
                                #_(dom/text #js {:x 15
                                               :y 15} (pr-str (dissoc payload :layers)))]
                               [(when (cameras/guidelines-enabled? payload)
                                  ;; TODO: Render guidelines
                                  )]))))))
