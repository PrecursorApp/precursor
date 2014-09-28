(ns frontend.components.aside
  (:require [frontend.components.common :as common]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]]))

(defn menu [app]
  (reify
    om/IRender
    (render [_]
      (html
        [:div.aside-menu
         [:button
          (common/icon :precursor-logo)
          [:span "Precursor"]]
         [:button
          (common/icon :precursor-logo)
          [:span "test"]]
         [:button
          (common/icon :precursor-logo)
          [:span "test"]]
         [:button
          (common/icon :precursor-logo)
          [:span "test"]]
         [:button
          (common/icon :precursor-logo)
          [:span "test"]]]))))
