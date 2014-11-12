(ns frontend.svg
  (:require [clojure.string :as str]
            [frontend.layers :as layers]
            [frontend.utils :as utils :include-macros true]))

(defn points->path [points]
  (str "M" (str/join " " (map (fn [p] (str (:rx p) " " (:ry p))) points))))

(defn layer->svg-rect [layer]
  (let [layer (layers/normalized-abs-coords layer)]
    (merge
     layer
     {:className     (str "shape-layer " (:className layer))
      :x             (:layer/start-x layer)
      :y             (:layer/start-y layer)
      :width         (- (or (:layer/current-x layer)
                            (:layer/end-x layer)) (:layer/start-x layer))
      :height        (- (or (:layer/current-y layer)
                            (:layer/end-y layer)) (:layer/start-y layer))
      :fill          (:layer/fill layer "none")
      :key           (:layer/id layer)
      :stroke        (cond
                      (:layer/selected? layer) "pink"
                      (:layer/hovered? layer) "green"
                      :else (:layer/stroke layer "black"))
      :strokeWidth   (cond
                      (:layer/selected? layer) 4
                      (:layer/hovered? layer) 4
                      :else (:layer/stroke-width layer 1))
      :rx            (:layer/rx layer)
      :ry            (:layer/ry layer)
      :strokeOpacity (cond
                      (:layer/selected? layer) 0.2
                      :else 1)})))

(defn layer->svg-text [layer]
  (merge
   layer
   {:className (str (:className layer) " text-layer")
    :x (:layer/start-x layer)
    :y (:layer/start-y layer)
    :fill (:layer/fill layer "none")
    :key (:layer/id layer)
    :stroke (:layer/stroke layer "black")
    ;; TODO: defaults for each layer when we create them
    :strokeWidth 0;(:layer/stroke-width layer 0)
    :fontFamily (:layer/font-family layer "Roboto")
    :fontSize   (:layer/font-size layer 24)}))

(defn layer->svg-line [layer]
  {:x1          (:layer/start-x layer)
   :y1          (:layer/start-y layer)
   :x2          (:layer/end-x layer)
   :y2          (:layer/end-y layer)
   :strokeWidth (:layer/stroke-width layer)})

(defn layer->svg-path [layer]
  {:d (:layer/path layer)
   :stroke (:layer/stroke layer "black")
   :fill "none"
   :strokeWidth (:layer/stroke-width layer)})
