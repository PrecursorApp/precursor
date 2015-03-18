(ns frontend.cursors
  (:require [frontend.state :as state]
            [frontend.utils :as utils]
            [om.core :as om]))

(defn observe [owner korks]
  (let [ks (if (sequential? korks) korks [korks])
        root (om/root-cursor (om/get-shared owner [:_app-state-do-not-use]))
        cursor (get-in root ks)]
    (assert (not (nil? cursor)) ks)
    (om/observe owner (om/ref-cursor cursor))))

(defn observe-mouse [owner]
  (observe owner [:mouse]))

(defn observe-subscriber-mice [owner]
  (observe owner [:subscribers :mice]))

(defn observe-subscriber-layers [owner]
  (observe owner [:subscribers :layers]))

(defn observe-subscriber-entity-ids [owner]
  (observe owner [:subscribers :entity-ids]))

(defn observe-selected-eids [owner]
  (observe owner [:selected-eids]))

(defn observe-editing-eids [owner]
  (observe owner [:editing-eids]))

(defn observe-drawing [owner]
  (observe owner [:drawing]))

(defn observe-camera [owner]
  (observe owner [:camera]))
