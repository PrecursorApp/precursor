(ns frontend.components.rtc
  (:require [frontend.utils :as utils]
            [om.core :as om]
            [om.dom :as dom]))

(defn rtc [app owner]
  (reify
    om/IDisplayName (display-name [_] "RTC")
    om/IRender
    (render [_]
      (apply dom/div nil
             (for [{:keys [stream]} (vals (get-in app [:subscribers :info]))
                   :when stream]
               (dom/audio #js {:autoPlay true
                               :key (aget stream "id")
                               :src stream}))))))
