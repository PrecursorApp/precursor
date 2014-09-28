(ns frontend.svg
  (:require [frontend.camera :as cameras]
            [frontend.layers :as layers]))

(defn layer->svg-rect [camera layer shape?]
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
     {:x           (:start-x layer)
      :y           (:start-y layer)
      :width       (- (or (:current-x layer)
                          (:end-x layer)) (:start-x layer))
      :height      (- (or (:current-y layer)
                          (:end-y layer)) (:start-y layer))
      :fill        (:fill layer "none")
      :key         (:id layer)
      :stroke      (cond
                    (:selected? layer) "pink"
                    (:hovered? layer) "green"
                    :else (:stroke layer "black"))
      :strokeWidth (cond
                    (:selected? layer) 4
                    (:hovered? layer) 4
                    :else (:stroke-width layer 1))
      :strokeOpacity (cond
                      (:selected? layer) 0.2
                      :else 1)
      :transform   (let [{:keys [n ox oy]} (get-in layer [:transforms :rotate])]
                     (when (and n (integer? ox) (integer? oy))
                       (str "rotate(" n "," (+ (:start-x layer) ox) ", " (+ (:start-y layer) oy) ")")))
      :style       {:opacity (:opacity layer)}})))
