(ns frontend.svg
  (:require [clojure.string :as str]
            [frontend.camera :as cameras]
            [frontend.layers :as layers]))

(defn points->path [points]
  (str "M" (str/join " " (map (fn [p] (str (:rx p) " " (:ry p))) points))))

(defn layer->svg-rect [camera layer shape? cast!]
  (let [layer (layers/normalized-abs-coords layer)
        layer (if shape?
                  (cameras/camera-translated-rect camera
                                                  layer
                                                  (layers/rect-width layer)
                                                  (layers/rect-height layer)
                                                  (get-in layer [:offset :x])
                                                  (get-in layer [:offset :y]))
                  layer)]
    (merge
     {:className     (when shape? "layer")
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
      :rx            (:layer/border-radius layer)
      :ry            (:layer/border-radius layer)
      :strokeOpacity (cond
                      (:layer/selected? layer) 0.2
                      :else 1)
      :transform     (let [{:keys [n ox oy]} (get-in layer [:transforms :rotate])]
                       (when (and n (integer? ox) (integer? oy))
                         (str "rotate(" n "," (+ (:layer/start-x layer) ox) ", " (+ (:layer/start-y layer) oy) ")")))
      :style         {:opacity (:layer/opacity layer)}
      :onClick       #(cast! :layer-selected [(:db/id layer)])})))
