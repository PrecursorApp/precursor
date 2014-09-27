(ns frontend.components.placeholder
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [frontend.async :refer [put!]]
            [frontend.utils :as utils :include-macros true]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]]))

(defn placeholder [app owner]
  (reify
    om/IRender
    (render [_]
      (html
       [:section "You navigated to build " (pr-str (get-in app [:inspected-project]))]))))
