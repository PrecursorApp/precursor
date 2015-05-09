(ns frontend.overlay
  (:require [clojure.set :as set]
            [frontend.state :as state]))

(defn clear-overlays [state]
  (assoc-in state state/overlays-path []))

(defn add-overlay
  "Adds overlay, or takes the overlay state back to the point where the
   given overlay appears in the stack.
  [:a :b :c] -> add :b -> [:a :b]
  [:a :b :c] -> add :e -> [:a :b :c :e]"
  [state overlay]
  (let [overlays (get-in state state/overlays-path)]
    (loop [next-overlays []
           remaining-overlays overlays]
      (if (or (= overlay (first remaining-overlays))
              (empty? remaining-overlays))
        (assoc-in state state/overlays-path (conj next-overlays overlay))
        (recur (conj next-overlays (first remaining-overlays))
               (rest remaining-overlays))))))

(defn safe-pop [v]
  (if (empty? v)
    v
    (pop v)))

(defn pop-overlay [state]
  (update-in state state/overlays-path safe-pop))

(defn replace-overlay [state overlay]
  (assoc-in state state/overlays-path [overlay]))

(defn current-overlay [state]
  (last (get-in state state/overlays-path)))

(defn overlay-visible? [state]
  (last (get-in state state/overlays-path)))

(defn overlay-count [state]
  (count (get-in state state/overlays-path)))

(def roster-overlays #{:roster :team-settings :team-doc-viewer :plan :your-teams
                       :request-team-access})

(defn roster-overlay? [overlay-key]
  (or (contains? roster-overlays overlay-key)
      (contains? roster-overlays (keyword (namespace overlay-key)))))

(defn app-overlay-class [state]
  (when (overlay-visible? state)
    (str " state-menu "
         (if (roster-overlay? (current-overlay state))
           " state-menu-right "
           " state-menu-left "))))

(defn roster-overlay-visible? [state]
  (some roster-overlays (get-in state state/overlays-path)))

(defn menu-overlay-visible? [state]
  (and (overlay-visible? state)
       (seq (set/difference (set (get-in state state/overlays-path)) roster-overlays))))

(defn add-issues-overlay [state]
  (let [overlays (conj (vec (remove #(or (keyword-identical? % :issues)
                                         (= "issues" (namespace %)))
                                    (get-in state state/overlays-path)))
                       :issues)]
    (-> state
      (assoc-in [:layer-properties-menu :opened?] false)
      (assoc-in [:radial :open?] false)
      (assoc-in state/overlays-path overlays))))

(defn handle-add-menu [state menu]
  (-> state
      (assoc-in [:layer-properties-menu :opened?] false)
      (assoc-in [:radial :open?] false)
      (add-overlay menu)))

(defn handle-replace-menu [state menu]
  (-> state
      (assoc-in [:layer-properties-menu :opened?] false)
      (assoc-in [:radial :open?] false)
      (replace-overlay menu)))
