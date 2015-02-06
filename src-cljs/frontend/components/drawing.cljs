(ns frontend.components.drawing
  (:require [frontend.state :as state]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

(defn signup-button [_ owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (let [cast! (om/get-shared owner :cast!)]
        (dotimes [x 100]
          (js/setTimeout #(cast! :subscriber-updated {:client-id (ffirst state/subscriber-bot)
                                                      :fields {:mouse-position [(+ 100 (* 6 x)) (+ 100 (* 5 x))]
                                                               :tool :rect}})
                         (* 25 x)))))
    om/IRender
    (render [_]
      ;; dummy span so that the component can be mounted
      (dom/span #js {:className "hidden"}))))
