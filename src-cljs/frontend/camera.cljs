(ns frontend.camera)

(def max-zoom 5)
(def min-zoom 0.1)
(def zoom-increment 0.1)

(defn camera [state]
  (:camera state))

(defn position [camera]
  (select-keys camera [:x :y :zf :z-exact]))

(defn reset [camera]
  (let [new-camera (assoc camera :x 0 :y 0 :zf 1 :z-exact 1)]
    (if (and (:previous camera) (= (position new-camera) (position camera)))
      new-camera
      (assoc new-camera :previous camera))))

(defn previous [camera]
  (if (:previous camera)
    (:previous camera)
    camera))

(defn snap [increment value]
  (js/parseFloat (.toFixed (* increment (js/Math.round (/ value increment))) 2)))

(defn grid-width [camera]
  (* (:zf camera) 10))

(defn grid-height [camera]
  (* (:zf camera) 10))

(defn show-bboxes? [state]
  false)

(defn guidelines-enabled? [state]
  false)

(defn show-grid? [state]
  (get-in state [:camera :show-grid?]))

(defn bounded [lower-bound upper-bound value]
  (max lower-bound (min upper-bound value)))

(defn set-zoom [state f]
  (let [old-z-exact (get-in state [:camera :z-exact])
        new-z-exact (bounded min-zoom max-zoom (f old-z-exact))]
    (-> state
        (assoc-in [:camera :z-exact] new-z-exact)
        (assoc-in [:camera :zf] (snap zoom-increment new-z-exact)))))

(defn move-camera [state dx dy]
  (-> state
   (update-in [:camera :x] + dx)
   (update-in [:camera :y] + dy)))

(defn screen-event-coords [event]
  [(.. event -pageX)
   (.. event -pageY)])

(defn grid-size->snap-increment
  "Step function to determine snap increment"
  [grid-size]
  (condp > grid-size
    3 100
    4 50
    8 25
    15 10
    50 5
    5))

(defn snap-to-grid [camera x y]
  (let [increment (grid-size->snap-increment (grid-width camera))]
    [(* increment (js/Math.round (/ x increment)))
     (* increment (js/Math.round (/ y increment)))]))

(defn screen->point [camera x y]
  [(/ (- x (:x camera) (:offset-x camera))
      (:zf camera))
   (/ (- y (:y camera) (:offset-y camera))
      (:zf camera))])

(defn ->svg-transform [camera]
  (str "translate(" (:x camera) " " (:y camera) ") "
       "scale(" (:zf camera) ")"))

(defn camera-translated-rect [camera rect w h & [off-x off-y]]
  (let [off-x    (or off-x 0)
        off-y    (or off-y 0)
        scaled-w (* (get-in rect [:transforms :scale :x] 1) w)
        scaled-h (* (get-in rect [:transforms :scale :y] 1) h)
        cx       (+ (:layer/start-x rect) (/ w 2))
        cy       (+ (:layer/start-y rect) (/ h 2))
        sx       (- cx (/ scaled-w 2))
        sy       (- cy (/ scaled-h 2))]
    (assoc rect
      :layer/start-x (+ (:x camera) (* (:zf camera) sx))
      :layer/end-x   (+ (:x camera) (* (:zf camera) (+ sx scaled-w)))
      :layer/start-y (+ (:y camera) (* (:zf camera) sy))
      :layer/end-y   (+ (:y camera) (* (:zf camera) (+ sy scaled-h))))))
