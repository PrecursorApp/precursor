(ns frontend.components.canvas
  (:require [frontend.camera :as cameras]
            [frontend.settings :as settings]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]]))

(defmulti svg-element (fn [state shape] (:type shape)))

(defmethod svg-element :default
  [state shape]
  (print "No svg element for " shape))

(defn state->cursor [state]
  "default")

(defn svg-canvas [payload owner opts]
  (reify
    om/IRender
    (render [_]
      (apply dom/svg (concat [#js {:width "100%"
                                   :height "100%"
                                   :id "svg-canvas"
                                   :xmlns "http://www.w3.org/2000/svg"
                                   :style #js {:top    0
                                               :left   0
                                               :cursor (state->cursor payload)
                                               }
                                   :onMouseDown (fn [event]
                                                  (.preventDefault event)
                                                  (.stopPropagation event)
                                                  ;;(.addEventListener js/document "mousemove" handle-mouse-move false)
                                                  ((:handle-mouse-down opts) event))
                                   :onMouseUp (fn [event]
                                                (.preventDefault event)
                                                (.stopPropagation event)
                                                ;;(.removeEventListener js/document "mousemove" handle-mouse-move false)
                                                ((:handle-mouse-up opts) event))
                                   :onWheel (fn [event]
                                              (.preventDefault event)
                                              (.stopPropagation event)
                                              (let [dx     (aget event "deltaX")
                                                    dy     (- (aget event "deltaY"))]
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
                             [(dom/text #js {:x (get-in payload [:mouse :x])
                                             :y (get-in payload [:mouse :y])
                                             :fill "green"}
                                        (pr-str (:mouse payload)))]
                             [(when-let [sel (cond
                                              (settings/selection-in-progress? payload) (settings/selection payload)
                                              (settings/drawing-in-progress? payload) (settings/drawing payload)
                                              :else nil)])
                              (dom/text #js {:x 15
                                             :y 15} (pr-str (dissoc payload :shapes)))]
                             [(when (cameras/guidelines-enabled? payload)
                                ;; TODO: Render guidelines
                                )])))))
