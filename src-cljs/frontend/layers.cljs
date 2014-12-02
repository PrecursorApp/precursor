(ns frontend.layers)

(defn layers [state]
  (:shapes state))

(defn normalized-abs-coords [coords]
  (cond-> coords
          (> (:layer/start-x coords) (or (:layer/current-x coords)
                                         (:layer/end-x coords)))
          (assoc
              :layer/start-x (or (:layer/current-x coords)
                                 (:layer/end-x coords))
              :layer/current-x (:layer/start-x coords)
              :layer/end-x (:layer/start-x coords))

          (> (:layer/start-y coords) (or (:layer/current-y coords)
                                         (:layer/end-y coords)))
          (assoc
              :layer/start-y (or (:layer/current-y coords)
                                 (:layer/end-y coords))
              :layer/current-y (:layer/start-y coords)
              :layer/end-y (:layer/start-y coords))))

(defn rect-width [rect]
  (- (:layer/end-x rect) (:layer/start-x rect)))

(defn rect-height [rect]
  (- (:layer/end-y rect) (:layer/start-y rect)))

(defmulti abs-bounding-box :layer.type)

(defmethod abs-bounding-box :layer.type/rect
  [rect]
  (let [w        (rect-width rect)
        h        (rect-height rect)
        scaled-w (* (get-in rect [:transforms :scale :x] 1) w)
        scaled-h (* (get-in rect [:transforms :scale :y] 1) h)
        cx       (+ (:layer/start-x rect) (/ w 2))
        cy       (+ (:layer/start-y rect) (/ h 2))
        sx       (- cx (/ scaled-w 2))
        sy       (- cy (/ scaled-h 2))
        ex       (+ cx (/ scaled-w 2))
        ey       (+ cy (/ scaled-h 2))]
    {:layer/start-x sx
     :layer/start-y sy
     :layer/end-x   ex
     :layer/end-y   ey}))

(defn make-layer [entity-id document-id x y]
  {:db/id              entity-id
   :document/id        document-id
   :layer/type         :layer.type/rect
   :layer/start-x      x
   :layer/start-y      y
   :layer/end-x        x
   :layer/end-y        y
   :layer/fill         "none"
   :layer/stroke-width 1
   :layer/stroke-color "black"
   :layer/name         "placeholder"
   :layer/opacity      1
   :entity/type        :layer})

;; TODO: only the draw-in-progress-drawing function should have to care about layer/current-x
(defmulti force-even (fn [layer] (:layer/type layer)))

(defmethod force-even :default
  [layer]
  layer)

(defmethod force-even :layer.type/rect
  [layer]
  (let [width (Math/abs (- (:layer/start-x layer)
                           (or (:layer/current-x layer)
                               (:layer/end-x layer))))
        height-sign (if (neg? (- (:layer/start-y layer)
                                 (or (:layer/current-y layer)
                                     (:layer/end-y layer))))
                      1
                      -1)
        end-y (+ (:layer/start-y layer) (* height-sign width))]
    (assoc layer
      :layer/current-y end-y
      :layer/end-y end-y)))

(defmethod force-even :layer.type/line
  [layer]
  (let [width (- (:layer/start-x layer)
                 (or (:layer/current-x layer)
                     (:layer/end-x layer)))
        width-sign (if (neg? width) 1 -1)
        height (- (:layer/start-y layer)
                  (or (:layer/current-y layer)
                      (:layer/end-y layer)))
        height-sign (if (neg? height) 1 -1)
        length (js/Math.sqrt (+ (* width width)
                                (* height height)))
        theta (js/Math.asin (/ (Math/abs height) length))
        pi-over-4 (/ js/Math.PI 4)
        even-theta (* pi-over-4 (Math/round (/ theta pi-over-4)))
        new-width (* (Math/cos even-theta) length width-sign)
        new-height (* (Math/sin even-theta) length height-sign)]
    (if (and (zero? height)
             (zero? width))
      layer
      (assoc layer
        :layer/current-x (+ (:layer/start-x layer) new-width)
        :layer/end-x (+ (:layer/start-x layer) new-width)
        :layer/current-y (+ (:layer/start-y layer) new-height)
        :layer/end-y (+ (:layer/start-y layer) new-height)))))
