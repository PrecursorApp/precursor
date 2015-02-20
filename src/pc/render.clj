(ns pc.render
  (:require [pc.svg :as svg]
            [hiccup.core :refer (h html)]))

(defmulti svg-element (fn [layer opts] (:layer/type layer)))

(defmethod svg-element :layer.type/rect
  [layer opts]
  [:rect (svg/layer->svg-rect layer opts)])

(defmethod svg-element :layer.type/text
  [layer opts]
  [:text (svg/layer->svg-text layer opts) (h (:layer/text layer))])

(defmethod svg-element :layer.type/line
  [layer opts]
  [:line (svg/layer->svg-line layer opts)])

(defmethod svg-element :layer.type/path
  [layer opts]
  [:path (svg/layer->svg-path layer opts)])

;; Getting placement here is a bit tricky.
;; Goal is to reproduce the canvas exactly as it is in the app, except in
;; black-and-white so they can print it.
;; If they've only drawn in positive x and y coordinates, then we're good
;; If they've drawn in negative directions, then we to shift the viewport in the
;; that direction with a transform.
(defn render-layers [layers & {:keys [invert-colors? size-limit]}]
  (let [layers (map #(into {} %) (filter #(not= :layer.type/group (:layer/type %)) layers))
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
                 (- max-y min-y))
        offset-top (if (neg? min-y)
                     (+ 250 (- min-y))
                     0)
        offset-left (if (neg? min-x)
                      (+ 250 (- min-x))
                      0)]
    (html [:svg (merge
                 {:width (apply min (concat [(+ width 500)]
                                            (when size-limit
                                              [size-limit])))
                  :height (apply min (concat [(+ height 500)]
                                             (when size-limit
                                               [size-limit])))
                  :xmlns "http://www.w3.org/2000/svg"
                  :xmlns:xlink "http://www.w3.org/1999/xlink"
                  :version "1.1"}
                 (when invert-colors?
                   {:style "background: #333"}))
           ;; hack to make pngs work
           (when invert-colors?
             [:rect {:width "100%" :height "100%" :fill "#333"}])
           [:g {:transform (format "translate(%s, %s)" offset-left offset-top)}
            (map #(svg-element % {:invert-colors? invert-colors?}) layers)]])))
