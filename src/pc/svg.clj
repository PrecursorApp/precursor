(ns pc.svg
  (:require [clojure.string :as str]
            [pc.layers :as layers]))

(defn points->path [points]
  (str "M" (str/join " " (map (fn [p] (str (:rx p) " " (:ry p))) points))))

(defn layer->svg-rect [layer]
  (let [layer (layers/normalized-abs-coords layer)]
    {:x             (:layer/start-x layer)
     :y             (:layer/start-y layer)
     :width         (- (or (:layer/current-x layer)
                           (:layer/end-x layer)) (:layer/start-x layer))
     :height        (- (or (:layer/current-y layer)
                           (:layer/end-y layer)) (:layer/start-y layer))
     :fill          "none"
     :key           (:layer/id layer)
     :stroke        "#ccc"
     :stroke-width   2
     :rx            (:layer/rx layer)
     :ry            (:layer/ry layer)     }))

(defn layer->svg-text [layer]
  {:x (:layer/start-x layer)
   :y (:layer/start-y layer)
   :fill "#ccc"
   :stroke-width 0
   :font-family (:layer/font-family layer "Helvetica")
   :font-size   (:layer/font-size layer 24)})

(defn layer->svg-line [layer]
  {:x1          (:layer/start-x layer)
   :y1          (:layer/start-y layer)
   :x2          (:layer/end-x layer)
   :y2          (:layer/end-y layer)
   :stroke "#ccc"
   :stroke-width 2})

(defn layer->svg-path [layer]
  {:d (:layer/path layer)
   :stroke "#ccc"
   :fill "none"
   :stroke-width 2})
