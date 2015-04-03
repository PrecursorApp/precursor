(ns frontend.keyboard
  (:require [frontend.state :as state]
            [frontend.utils :as utils]))

(defn arrow-shortcut-key-paths [app]
  (for [shortcut (get-in app [:keyboard-shortcuts :arrow-tool])]
    [:keyboard shortcut]))

(defn arrow-shortcut-state-keys [app]
  (concat
   [[:keyboard-shortcuts :arrow-tool]]
   (arrow-shortcut-key-paths app)))

(defn arrow-shortcut-active? [app]
  (first (filter #(get-in app %) (arrow-shortcut-key-paths app))))

(defn pan-shortcut-active? [app]
  (get-in app [:keyboard #{"space"}]))
