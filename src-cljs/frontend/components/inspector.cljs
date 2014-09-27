(ns frontend.components.inspector
  (:require [ankha.core :as ankha]
            [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [frontend.async :refer [put!]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [sablono.core :as html :refer-macros [html]]))

(defn inspector [app owner]
  (reify
    om/IDisplayName (display-name [_] "Inspector")
    om/IRender
    (render [_]
      (html
       [:code {:style {:flex "0 0 40%"}} (om/build ankha/inspector app)]))))
