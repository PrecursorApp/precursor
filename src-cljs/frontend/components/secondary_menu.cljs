(ns frontend.components.secondary-menu
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]]))

(defn menu-item [cast! text msg class svg-source]
  (dom/li #js {:style #js {:marginTop 4
                           :listStyleType "none"}
               :className class
               :onClick (fn [event] (cast! msg))}
          (when svg-source
            (dom/img #js {:src svg-source
                          :className "menu-item-icon"
                          :style #js {:float "right"
                                      :width 15
                                      :height 15}}))
               (dom/span nil text)))

(defn menu [payload owner opts]
  (om/component
   (let [cast! (om/get-shared owner :cast!)
         menu-items [["cut" :selection-cut nil nil]
                     ["copy" :selection-copied nil nil]
                     ["paste" :selection-pasted nil nil]
                     ["duplicate" :selection-duplicated nil nil]
                     ["delete" :selection-deleted "group-end" nil]
                     ["move forward" :selection-moved-forward "" nil]
                     ["move backward" :selection-moved-backward "group-end" nil]
                     ["group" :selection-grouped nil nil]
                     ["ungroup" :selection-ungrouped "group-end" nil]
                     ["transform" :selection-transformed nil "img/transform-icon.svg"]
                     ["rotate +90º" :selection-rotated-90right nil "img/rotate-right-icon.svg"]
                     ["rotate -90º" :selection-rotated-90left nil "img/rotate-left-icon.svg"]
                     ["flip horizontal" :selection-flipped-horizontally nil "img/flip-horizontal-icon.svg"]
                     ["flip vertical" :selection-flipped-vertically "group-end" "img/flip-vertical-icon.svg"]
                     ["align top" :selection-aligned-top nil "img/align-top-icon.svg"]
                     ["align middle" :selection-aligned-middle nil "img/align-middle-icon.svg"]
                     ["align bottom" :selection-aligned-bottom "group-end" "img/align-bottom-icon.svg"]
                     ["align left" :selection-aligned-left nil "img/align-left-icon.svg"]
                     ["align center" :selection-aligned-center nil "img/align-center-icon.svg"]
                     ["align right" :selection-aligned-right "group-end" "img/align-right-icon.svg"]
                     ["distribute horizontally" :selection-distributed-horizontally nil nil]
                     ["distribute vertically" :selection-distributed-vertically nil nil]]
         x 100
         y 50]
     (html
      (dom/div #js {:style #js {:position "absolute"
                                :top y
                                :left x
                                :width 163
                                :height 490
                                :backgroundColor "rgba(255, 255, 255, 0.7)"
                                :border "solid 1px black"
                                }}
               (apply dom/ul #js {:style #js {:padding 5
                                              :marginTop -4}}
                       (map (fn [item]
                              (menu-item cast! (first item) (second item) (nth item 2) (nth item 3))) menu-items)))))))

