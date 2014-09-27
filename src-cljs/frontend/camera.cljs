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
