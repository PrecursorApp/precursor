(ns pc.svg
  (:require [clojure.string :as str]
            [hiccup.core :as h]
            [pc.layers :as layers]))

(defn points->path [points]
  (str "M" (str/join " " (map (fn [p] (str (:rx p) " " (:ry p))) points))))

(defn layer->svg-rect [layer {:keys [invert-colors? layer-props]}]
  (let [layer (layers/normalized-abs-coords layer)]
    (merge
     {:x             (:layer/start-x layer)
      :y             (:layer/start-y layer)
      :width         (- (or (:layer/current-x layer)
                            (:layer/end-x layer)) (:layer/start-x layer))
      :height        (- (or (:layer/current-y layer)
                            (:layer/end-y layer)) (:layer/start-y layer))
      :fill          "none"
      :key           (:layer/id layer)
      :stroke        (if invert-colors? "#ccc" "black")
      :stroke-width   2
      :vector-effect  "non-scaling-stroke"
      :rx            (:layer/rx layer)
      :ry            (:layer/ry layer)}
     layer-props)))

(defn layer->svg-text [layer {:keys [invert-colors? layer-props]}]
  (merge {:x (:layer/start-x layer)
          :y (:layer/start-y layer)
          :fill (if invert-colors? "#ccc" "black")
          :stroke-width 0
          :font-family (h/h (:layer/font-family layer "Roboto"))
          :font-size   (:layer/font-size layer 20)}
         layer-props))

(defn layer->svg-line [layer {:keys [invert-colors? layer-props]}]
  (merge
   {:x1          (:layer/start-x layer)
    :y1          (:layer/start-y layer)
    :x2          (:layer/end-x layer)
    :y2          (:layer/end-y layer)
    :stroke (if invert-colors? "#ccc" "black")
    :stroke-width 2
    :vector-effect  "non-scaling-stroke"}
   layer-props))

(defn layer->svg-path [layer {:keys [invert-colors? layer-props]}]
  (merge {:d (h/h (:layer/path layer))
          :stroke (if invert-colors? "#ccc" "black")
          :fill "none"
          :stroke-width 2
          :vector-effect  "non-scaling-stroke"}
         layer-props))
