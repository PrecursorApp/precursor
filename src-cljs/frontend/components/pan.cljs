(ns frontend.components.pan
  (:require [frontend.components.common :as common]
            [frontend.cursors :as cursors]
            [frontend.utils :as utils]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

;; defined in js instead of css b/c we rely on the value to get the positioning correct
;; can put it in css if top and left don't need to be corrected for cursor size
(def cursor-size 16)

(def inactive-pan-cursor (common/svg-icon :crosshair
                                          {:svg-props {:style {:height cursor-size
                                                               :width cursor-size}
                                                       :x 0
                                                       :y 0}}))

(def active-pan-cursor (common/svg-icon :crosshair
                                        {:svg-props {:style {:height cursor-size
                                                             :width cursor-size}
                                                     :x 0
                                                     :y 0}}))

(defn pan-cursor [{:keys [keyboard mouse-down]} owner]
  (reify
    om/IRender
    (render [_]
      (let [pan (cursors/observe-pan-tool owner)]
        (if (and (get keyboard #{"space"})
                 (:position pan))
          (dom/div #js {:className (str "pan-cursor "
                                        (when mouse-down
                                          "active "))
                        :style #js {:top (- (get-in pan [:position :y]) (/ cursor-size 2))
                                    :left (- (get-in pan [:position :x]) (/ cursor-size 2))
                                    :position "fixed"
                                    :width cursor-size
                                    :height cursor-size}}
            (if (utils/inspect mouse-down)
              active-pan-cursor
              inactive-pan-cursor))
          (dom/div #js {:style #js {:display "none"}}))))))
