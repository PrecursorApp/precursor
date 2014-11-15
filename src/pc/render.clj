(ns pc.render
  (:require [pc.svg :as svg]
            [hiccup.core :refer (h html)]))

(defmulti svg-element (fn [layer] (:layer/type layer)))

(defmethod svg-element :layer.type/rect
  [layer]
  [:rect (svg/layer->svg-rect layer)])

(defmethod svg-element :layer.type/text
  [layer]
  [:text (svg/layer->svg-text layer) (h (:layer/text layer))])

(defmethod svg-element :layer.type/line
  [layer]
  [:line (svg/layer->svg-line layer)])

(defmethod svg-element :layer.type/path
  [layer]
  [:path (svg/layer->svg-path layer)])

(defn render-layers [layers]
  (let [layers (filter #(not= :layer.type/group (:layer/type %)) layers)
        start-xs (remove #(.isNaN %) (map :layer/start-x layers))
        start-ys (remove #(.isNaN %) (map :layer/start-y layers))
        end-xs (remove #(.isNaN %) (map :layer/end-x layers))
        end-ys (remove #(.isNaN %) (map :layer/end-y layers))
        xs (or (seq (concat start-xs end-xs)) [0])
        ys (or (seq (concat start-ys end-ys)) [0])
        min-x (apply min xs)
        min-y (apply min ys)
        max-x (apply max xs)
        max-y (apply max ys)
        width (if (pos? min-x)
                max-x
                (- max-x min-x))
        height (if (pos? min-y)
                 max-y
                 (- max-y min-y))]
    (html [:svg {:width (+ width 500)
                 :height (+ height 500)
                 :xmlns "http://www.w3.org/2000/svg"
                 :xmlns:xlink "http://www.w3.org/1999/xlink"
                 :version "1.1"}
           (map svg-element layers)])))
