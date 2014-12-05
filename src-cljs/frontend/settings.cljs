(ns frontend.settings
  (:require [frontend.models.layer :as layer-model]))

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

(defn selected-eids [state]
  (layer-model/selected-eids @(:db state)
                             (:selected-eid state)))
