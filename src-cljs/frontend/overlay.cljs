(ns frontend.overlay
  (:require [frontend.state :as state]))

(defn clear-overlays [state]
  (assoc-in state state/overlays-path []))

(defn add-overlay [state overlay]
  (update-in state state/overlays-path conj overlay))

(defn pop-overlay [state]
  (update-in state state/overlays-path pop))

(defn replace-overlay [state overlay]
  (assoc-in state state/overlays-path [overlay]))

(defn current-overlay [state]
  (last (get-in state state/overlays-path)))

(defn overlay-visible? [state]
  (last (get-in state state/overlays-path)))

(defn overlay-count [state]
  (count (get-in state state/overlays-path)))
