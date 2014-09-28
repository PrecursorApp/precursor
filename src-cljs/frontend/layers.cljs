(ns frontend.layers)

(defn layers [state]
  (:shapes state))

(defn normalized-abs-coords [coords]
  (cond-> coords
          (> (:start-x coords) (or (:current-x coords)
                                   (:end-x coords)))
          (assoc
              :start-x (or (:current-x coords)
                           (:end-x coords))
              :current-x (:start-x coords)
              :end-x (:start-x coords))

          (> (:start-y coords) (or (:current-y coords)
                                   (:end-y coords)))
          (assoc
              :start-y (or (:current-y coords)
                           (:end-y coords))
              :current-y (:start-y coords)
              :end-y (:start-y coords))))

(defn rect-width [rect]
  (- (:end-x rect) (:start-x rect)))

(defn rect-height [rect]
  (- (:end-y rect) (:start-y rect)))

(defmulti abs-bounding-box :type)

(defmethod abs-bounding-box :rect
  [rect]
  (let [w        (rect-width rect)
        h        (rect-height rect)
        scaled-w (* (get-in rect [:transforms :scale :x] 1) w)
        scaled-h (* (get-in rect [:transforms :scale :y] 1) h)
        cx       (+ (:start-x rect) (/ w 2))
        cy       (+ (:start-y rect) (/ h 2))
        sx       (- cx (/ scaled-w 2))
        sy       (- cy (/ scaled-h 2))
        ex       (+ cx (/ scaled-w 2))
        ey       (+ cy (/ scaled-h 2))]
    {:start-x sx
     :start-y sy
     :end-x   ex
     :end-y   ey}))
