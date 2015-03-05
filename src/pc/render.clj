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

(defn nan? [thing]
  (or (not thing) (.isNaN thing)))

;; Getting placement here is a bit tricky.
;; Goal is to reproduce the canvas exactly as it is in the app, except in
;; black-and-white so they can print it.
;; If they've only drawn in positive x and y coordinates, then we're good
;; If they've drawn in negative directions, then we to shift the viewport in the
;; that direction with a transform.
(defn render-layers [layers & {:keys [invert-colors? size-limit]}]
  (def mylayers layers)
  (let [layers (map #(into {} %) (filter #(and (:layer/type %)
                                               (not= :layer.type/group (:layer/type %))) layers))
        start-xs (remove nan? (map :layer/start-x layers))
        start-ys (remove nan? (map :layer/start-y layers))
        end-xs (remove nan? (map :layer/end-x layers))
        end-ys (remove nan? (map :layer/end-y layers))
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
        padding 100
        scale-factor (if size-limit
                       (let [max-dim (+ padding (max width height))]
                         (min 1 (/ size-limit max-dim)))
                       1)
        offset-top (if (neg? min-y)
                     (+ (/ padding 2) (- min-y))
                     0)
        offset-left (if (neg? min-x)
                      (+ (/ padding 2) (- min-x))
                      0)]
    (html [:svg (merge
                 {:width (* scale-factor (+ width padding))
                  :height (* scale-factor (+ height padding))
                  :xmlns "http://www.w3.org/2000/svg"
                  :xmlns:xlink "http://www.w3.org/1999/xlink"
                  :version "1.1"}
                 (when invert-colors?
                   {:style "background: #333"}))
           ;; hack to make pngs work
           (when invert-colors?
             [:rect {:width "100%" :height "100%" :fill "#333"}])
           [:g {:transform (format "translate(%s, %s) scale(%s)"
                                   (* scale-factor offset-left)
                                   (* scale-factor offset-top)
                                   scale-factor)}
            (map #(svg-element % {:invert-colors? invert-colors?}) layers)]])))
