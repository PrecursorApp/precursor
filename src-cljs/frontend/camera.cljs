(ns frontend.camera)

(defn camera [state]
  (:camera state))

(defn grid-width [state]
  (* (get-in state [:camera :zf]) 10))

(defn grid-height [state]
  (* (get-in state [:camera :zf]) 10))

(defn show-bboxes? [state]
  false)

(defn guidelines-enabled? [state]
  false)

(defn show-grid? [state]
  (get-in state [:camera :show-grid?]))

(defn set-zoom [state f]
  (update-in state [:camera :zf] f))

(defn move-camera [state dx dy]
  (-> state
   (update-in [:camera :x] + dx)
   (update-in [:camera :y] + dy)))

(defn camera-mouse-mode [state]
  (cond
   (get-in state [:keyboard :alt?]) :zoom
   :else :pan))

(defn screen-event-coords [event]
  [(.. event -pageX)
   (.. event -pageY)])

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
