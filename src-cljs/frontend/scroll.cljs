(ns frontend.scroll
  (:require [frontend.disposable :as disposable]
            [frontend.utils :as utils :include-macros true]
            [om.core :as om :include-macros true]
            [goog.events]))

(defn register [owner cb]
  (om/set-state! owner ::scroll-id
    (disposable/register
      (goog.events/listen
        js/window
        "scroll"
        (fn [event]
          (utils/swallow-errors (cb event))))
      (fn dispose [event-key]
        (goog.events/unlistenByKey event-key)))))

(defn dispose [owner]
  (disposable/dispose (om/get-state owner ::scroll-id)))
