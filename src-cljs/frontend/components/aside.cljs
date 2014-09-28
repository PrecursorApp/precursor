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
          (common/icon :logomark-precursor)
          [:span "Precursor"]]
         [:button
          (common/icon :logomark-precursor)
          [:span "About"]]
         [:button
          (common/icon :logomark-precursor)
          [:span "Collaborators"]]
         [:button
          (common/icon :download)
          [:span "Download"]]
         [:button
          (common/icon :settings)
          [:span "Settings"]]]))))
