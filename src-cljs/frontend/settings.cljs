(ns frontend.settings)

(defn selection-in-progress? [state]
  (get-in state [:selection :in-progress?]))

(defn drawing-in-progress? [state]
  (get-in state [:drawing :in-progress?]))

(defn selection [state]
  (get-in state [:selection :layer]))

(defn drawing [state]
  (get-in state [:drawing :layer]))
