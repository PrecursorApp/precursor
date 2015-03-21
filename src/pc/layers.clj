(ns pc.layers)

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
   :layer/document     document-id
   :layer/type         :layer.type/rect
   :layer/start-x      x
   :layer/start-y      y
   :layer/end-x        x
   :layer/end-y        y
   :layer/fill         "white"
   :layer/stroke-width 1
   :layer/stroke-color "black"
   :layer/name         "placeholder"
   :layer/opacity      1
   :entity/type        :layer})

(defn center [{:keys [layer/start-x layer/end-x layer/start-y layer/end-y]}]
  [(+ start-x (/ (- end-x start-x) 2))
   (+ start-y (/ (- end-y start-y) 2))])

(defn determinant [[zx zy :as zero-point] [ax ay] [bx by]]
   (- (* (- ax zx)
         (- by zy))
      (* (- bx zx)
         (- ay zy))))

;; much better ways to do this, e.g.
;; https://math.stackexchange.com/questions/69099/equation-of-a-rectangle/69134#69134
(defn layer-intercept
  "Takes layer, (x1, y1) = point outside the layer"
  [{:keys [layer/start-x layer/end-x layer/start-y layer/end-y] :as layer} [x1 y1] & {:keys [padding]
                                                                                      :or {padding 10}}]
  (let [max-x (+ padding (max start-x end-x))
        max-y (+ padding (max start-y end-y))
        min-x (+ (- padding) (min start-x end-x))
        min-y (+ (-  padding) (min start-y end-y))
        p (/ (- max-x min-x) 2)
        q (/ (- max-y min-y) 2)
        [cx cy] (center layer)
        quadrant (if (> x1 cx)
                   (if (< y1 cy)
                     1
                     4)
                   (if (< y1 cy)
                     2
                     3))
        corner (case quadrant
                 1 [max-x min-y]
                 2 [min-x min-y]
                 3 [min-x max-y]
                 4 [max-x max-y])
        d (determinant [cx cy] corner [x1 y1])
        m (if (= x1 cx)
            Double/POSITIVE_INFINITY
            (/ (- y1 cy)
               (- x1 cx)))]
    (if (zero? d)
      corner
      (let [[rx ry] (case quadrant
                      1 (if (pos? d)
                          [p (* m p)]
                          [(/ (-  q) m) (- q)])
                      2 (if (pos? d)
                          [(/ (- q) m) (- q)]
                          [(- p) (* m (- p))])
                      3 (if (pos? d)
                          [(- p) (* m (- p))]
                          [(/ q m) q])
                      4 (if (pos? d)
                          [(/ q m) q]
                          [p (* m p)]))]
        [(+ cx rx) (+ cy ry)]))))

(defn contains-point? [{:keys [layer/start-x layer/end-x layer/start-y layer/end-y]} [x y] & {:keys [padding]
                                                                                              :or {padding 0}}]
  (and (<= x (+ (max start-x end-x) padding))
       (<= y (+ (max start-y end-y) padding))
       (>= x (- (min start-x end-x) padding))
       (>= y (- (min start-y end-y) padding))))

(defn arrow-path [[x0 y0] [x1 y1]]
  (let [theta (Math/atan2 (determinant [x1 y1] [x0 y0] [(+ x1 1) y1])
                          (- x1 x0))
        theta-arrow (/ Math/PI 6)
        r 5
        t1 [(- x1 (* r (Math/cos (+ theta theta-arrow))))
            (- y1 (* r (Math/sin (+ theta theta-arrow))))]
        t2 [(- x1 (* r (Math/cos (- theta theta-arrow))))
            (- y1 (* r (Math/sin (- theta theta-arrow))))]
        cross-point [(- x1 (* r (Math/cos theta-arrow) (Math/cos theta)))
                     (- y1 (* r (Math/cos theta-arrow) (Math/sin theta)))]]
    (str "M "
         (clojure.string/join " " (concat [x0 y0]
                                          [x1 y1]
                                          cross-point
                                          t2
                                          [x1 y1]
                                          t1
                                          cross-point))
         " Z")))
