(ns frontend.components.aside
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]]))

(defn menu [app]
  (reify
    om/IRender
    (render [_]
      (html
        [:div.aside-menu
         [:button "test"]
         [:button "test"]
         [:button "test"]
         [:button "test"]
         [:button "test"]]))))
