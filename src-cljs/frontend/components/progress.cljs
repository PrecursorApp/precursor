(ns frontend.components.progress
  (:require [frontend.cursors :as cursors]
            [frontend.state :as state]
            [frontend.utils :as utils]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [sablono.core :refer (html)]))

(defn progress [app owner]
  (reify
    om/IDisplayName
    (display-name [_] "Progress Bar")
    om/IRender
    (render [_]
      (let [progress (cursors/observe-progress owner)]
        (html
          [:div.progress {:class (when (:active progress) "active")}
           [:div.progress-bar {:style {:transform (str "translateX("
                                                       (if (:active progress)
                                                         (str (- (:percent progress) 100) "%")
                                                         0)
                                                       ")")
                                       :transition-duration (str (+ 16 (:expected-tick-duration progress 0))
                                                                 "ms")}}]])))))
