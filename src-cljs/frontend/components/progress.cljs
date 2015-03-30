(ns frontend.components.progress
  (:require [frontend.cursors :as cursors]
            [frontend.state :as state]
            [frontend.utils :as utils]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [sablono.core :refer (html)]))

(defn progress-bar [app owner]
  (reify
    om/IDisplayName
    (display-name [_] "Progress Bar")
    om/IRender
    (render [_]
      (let [progress (cursors/observe-progress owner)]
        (html
         [:div.progress-bar {:class (when (:active progress) "active")}
          [:div.progress {:style {:width "100%"
                                  :transform (str "translate("
                                                  (if (:active progress)
                                                    (str (- (:percent progress) 100) "%")
                                                    0)
                                                  ")")
                                  :transition-duration (str (+ 16 (:expected-tick-duration progress 0))
                                                            "ms")}}]])))))
