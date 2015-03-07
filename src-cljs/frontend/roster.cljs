(ns frontend.roster
  (:require [frontend.state :as state]))

(defn clear-rosters [state]
  (assoc-in state state/rosters-path []))

(defn add-roster [state roster]
  (update-in state state/rosters-path conj roster))

(defn safe-pop [v]
  (if (empty? v)
    v
    (pop v)))

(defn pop-roster [state]
  (update-in state state/rosters-path safe-pop))

(defn replace-roster [state roster]
  (assoc-in state state/rosters-path [roster]))

(defn current-roster [state]
  (last (get-in state state/rosters-path)))

(defn roster-visible? [state]
  (last (get-in state state/rosters-path)))

(defn roster-count [state]
  (count (get-in state state/rosters-path)))
