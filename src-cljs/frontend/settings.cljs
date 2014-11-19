(ns frontend.settings)

(defn selection-in-progress? [state]
  (get-in state [:selection :in-progress?]))

(defn drawing-in-progress? [state]
  (get-in state [:drawing :in-progress?]))

(defn moving-drawing? [state]
  (get-in state [:drawing :moving?]))

(defn selection [state]
  (get-in state [:selection :layer]))

(defn drawing [state]
  (get-in state [:drawing :layers]))
