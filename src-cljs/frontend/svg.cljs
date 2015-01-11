(ns frontend.svg
  (:require [clojure.string :as str]
            [frontend.layers :as layers]
            [frontend.utils :as utils :include-macros true]))

(defn points->path [points]
  (str "M" (str/join " " (map (fn [p] (str (:rx p) " " (:ry p))) points))))

(defn layer->svg-rect [layer]
  (let [layer (layers/normalized-abs-coords layer)
        padding (get layer :padding 0)]
    (merge
     layer
     {:className     (str "shape-layer " (:className layer))
      :x             (+ (:layer/start-x layer) padding)
      :y             (+ (:layer/start-y layer) padding)
      :width         (- (or (:layer/current-x layer)
                            (:layer/end-x layer))
                        (:layer/start-x layer)
                        (* padding 2))
      :height        (- (or (:layer/current-y layer)
                            (:layer/end-y layer))
                        (:layer/start-y layer)
                        (* padding 2))
      :fill          (:layer/fill layer "none")
      :rx            (:layer/rx layer)
      :ry            (:layer/ry layer)})))

(defn layer->svg-text [layer]
  (merge
   layer
   {:className (str (:className layer) " text-layer")
    :x (+ 1 (:layer/start-x layer))
    :y (:layer/start-y layer)
    :key (:layer/id layer)
    ;; TODO: defaults for each layer when we create them
    :fontSize   (:layer/font-size layer 20)}))

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
