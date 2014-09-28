(ns frontend.layers)

(defn layers [state]
  (:shapes state))

(defn normalized-abs-coords [coords]
  (cond-> coords
          (> (:layer/start-x coords) (or (:current-x coords)
                                   (:layer/end-x coords)))
          (assoc
              :layer/start-x (or (:current-x coords)
                           (:layer/end-x coords))
              :current-x (:layer/start-x coords)
              :layer/end-x (:layer/start-x coords))

          (> (:layer/start-y coords) (or (:current-y coords)
                                   (:layer/end-y coords)))
          (assoc
              :layer/start-y (or (:current-y coords)
                           (:layer/end-y coords))
              :current-y (:layer/start-y coords)
              :layer/end-y (:layer/start-y coords))))

(defn rect-width [rect]
  (- (:layer/end-x rect) (:layer/start-x rect)))

(defn rect-height [rect]
  (- (:layer/end-y rect) (:layer/start-y rect)))

(defmulti abs-bounding-box :type)

(defmethod abs-bounding-box :rect
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
