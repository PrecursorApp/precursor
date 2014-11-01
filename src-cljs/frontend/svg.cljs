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
     {:className     "layer"
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
                      :else 1)
      :style         {:opacity (:layer/opacity layer)}})))
