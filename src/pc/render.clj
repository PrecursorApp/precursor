(ns pc.render
  (:require [clojure.java.io :as io]
            [pc.layers :as layers]
            [pc.svg :as svg]
            [pc.util.base64 :as base64]
            [pc.utils :as utils]
            [hiccup.core :refer (h html)])
  (:import [org.apache.commons.io IOUtils]))

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

(defn fonts* []
  {:roboto-regular (-> "public/webfonts/Roboto/RobotoRegular.ttf"
                     (io/resource)
                     (io/input-stream)
                     (IOUtils/toByteArray)
                     (base64/encode))})

(def fonts (memoize fonts*))

(defn svg-props [layers & {:keys [size-limit padding]
                           :or {padding 100}}]
  (let [start-xs (remove nan? (map :layer/start-x layers))
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
        scale-factor (if size-limit
                       (let [max-dim (+ padding (max width height))]
                         (min 1 (/ size-limit max-dim)))
                       1)
        offset-top (* (if (neg? min-y)
                        (+ (/ padding 2) (- min-y))
                        0)
                      scale-factor)
        offset-left (* (if (neg? min-x)
                         (+ (/ padding 2) (- min-x))
                         0)
                       scale-factor)]
    {:width (* scale-factor (+ width padding))
     :height (* scale-factor (+ height padding))
     :offset-top offset-top
     :offset-left offset-left
     :padding padding
     :scale-factor scale-factor}))

;; Getting placement here is a bit tricky.
;; Goal is to reproduce the canvas exactly as it is in the app, except in
;; black-and-white so they can print it.
;; If they've only drawn in positive x and y coordinates, then we're good
;; If they've drawn in negative directions, then we to shift the viewport in the
;; that direction with a transform.
(defn render-layers [layers & {:keys [invert-colors? size-limit] :as args}]
  (let [layers (map #(into {} %) (filter #(and (:layer/type %)
                                               (not= :layer.type/group (:layer/type %))) layers))
        {:keys [width height offset-top offset-left padding scale-factor]} (utils/apply-map svg-props layers args)]
    (html [:svg (merge
                 {:width width
                  :height height
                  :xmlns "http://www.w3.org/2000/svg"
                  :xmlns:xlink "http://www.w3.org/1999/xlink"
                  :version "1.1"}
                 (when invert-colors?
                   {:style "background: #333"}))
           [:defs
            [:style {:type "text/css"}
             (format "@font-face { font-family: 'Roboto'; src: url('data:application/x-font-ttf;base64, %s') format('truetype');"
                     (:roboto-regular (fonts)))]]
           ;; hack to make pngs work
           (when invert-colors?
             [:rect {:width "100%" :height "100%" :fill "#333"}])
           [:marker {:id "arrow-point"
                     :viewBox "0 0 10 10"
                     :refX 5
                     :refY 5
                     :markerUnits "strokeWidth"
                     :markerWidth 5
                     :markerHeight 5
                     :orient "auto"
                     :fill (if invert-colors? "#ccc" "black")}
            [:path {:d "M 0 0 L 10 5 L 0 10 z"}]]
           [:g {:transform (format "translate(%s, %s) scale(%s)"
                                   offset-left
                                   offset-top
                                   scale-factor)}
            (concat
             (map #(svg-element % {:invert-colors? invert-colors?}) layers)
             (mapcat (fn [layer]
                       (for [dest (:layer/points-to layer)
                             :let [origin layer
                                   dest (into {} dest)
                                   dest-center (layers/center dest)
                                   origin-center (layers/center origin)
                                   [start-x start-y] (layers/layer-intercept origin dest-center :padding 10)
                                   [end-x end-y] (layers/layer-intercept dest origin-center :padding 10)]
                             :when (not (or (= [start-x start-y]
                                               [end-x end-y])
                                            (layers/contains-point? dest [start-x start-y] :padding 10)
                                            (layers/contains-point? origin [end-x end-y] :padding 10)))]
                         (svg-element (assoc origin
                                             :layer/start-x start-x
                                             :layer/start-y start-y
                                             :layer/end-x end-x
                                             :layer/end-y end-y
                                             :layer/path (layers/arrow-path [start-x start-y] [end-x end-y])
                                             :layer/type :layer.type/path)
                                      {:invert-colors? invert-colors?})))
                     (filter :layer/points-to layers)))]])))
