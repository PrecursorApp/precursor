(ns frontend.components.progress
  (:require [frontend.cursors :as cursors]
            [frontend.state :as state]
            [frontend.utils :as utils]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [sablono.core :refer (html)]))

(defn progress-bar [app owner]
  (reify
    om/IRender
    (render [_]
      (let [progress (cursors/observe-progress owner)]
        (html
         [:div.progress-bar {:class (when (:active progress) "active")}
          [:div.progress {:style {:width (if (:active progress)
                                           (str (:percent progress) "%")
                                           "100%")}}]])))))
