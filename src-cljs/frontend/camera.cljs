(ns frontend.camera
  (:require [frontend.utils :as utils]))

(def max-zoom 5)
(def min-zoom 0.1)
(def zoom-increment 0.05)

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

(defn show-grid? [camera]
  (get-in camera [:show-grid?]))

(defn bounded [lower-bound upper-bound value]
  (max lower-bound (min upper-bound value)))

(defn set-zoom [camera screen-center f]
  (let [old-z-exact (get-in camera [:z-exact])
        new-z-exact (bounded min-zoom max-zoom (f old-z-exact))
        [x_s y_s] screen-center
        old-zf (get-in camera [:zf])
        new-zf (snap zoom-increment new-z-exact)]
    (-> camera
        (assoc-in [:z-exact] new-z-exact)
        (assoc-in [:zf] new-zf)
        (update-in [:x] (fn [x] (+ x (* (- x_s x) (- 1 (/ new-zf old-zf))))))
        (update-in [:y] (fn [y] (+ y (* (- y_s y) (- 1 (/ new-zf old-zf)))))))))

(defn x-left [camera viewport]
  (/ (- (:x camera)) (:zf camera)))

(defn x-right [camera viewport]
  (+ (/ (:width viewport) (:zf camera)) (x-left camera viewport)))

(defn y-top [camera viewport]
  (/ (- (:y camera)) (:zf camera)))

(defn y-bottom [camera viewport]
  (+ (/ (:height viewport) (:zf camera)) (y-top camera viewport)))

(defn top-left [camera viewport]
  [(x-left camera viewport)
   (y-top camera viewport)])

(defn top-right [camera viewport]
  [(x-right camera viewport)
   (y-top camera viewport)])

(defn bottom-left [camera viewport]
  [(x-left camera viewport)
   (y-bottom camera viewport)])

(defn bottom-right [camera viewport]
  [(x-right camera viewport)
   (y-bottom camera viewport)])

(defn move-camera [camera dx dy]
  (-> camera
   (update-in [:x] + dx)
   (update-in [:y] + dy)))

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
