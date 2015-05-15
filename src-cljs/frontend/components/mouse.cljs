(ns frontend.components.mouse
  (:require [frontend.components.common :as common]
            [frontend.cursors :as cursors]
            [frontend.keyboard :as keyboard]
            [frontend.state :as state]
            [frontend.utils :as utils]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

(def hints
  {:chat #js {:data-chat "Open chat." :className " hint holo east"}
   :menu #js {:data-menu "Open menu." :className " hint holo west"}
   :name #js {:data-name "Change your name." :className " hint holo west"}
   :team #js {:data-team "Open team menu." :className " hint holo east"}
   :dial #js {:data-dial "Right-click." :className " hint holo north fast"}})

(def grabbing-path "M4.5,4.8 C5,4.6,5.9,4.8,6.2,5.3c0.2,0.5,0.4,1.2,0.4,1.1c0-0.4,0-1.2,0.1-1.6c0.1-0.3,0.3-0.6,0.7-0.7C7.7,4,8,4,8.3,4 C8.6,4.1,9,4.3,9.1,4.5c0.4,0.6,0.4,1.9,0.4,1.8c0.1-0.3,0.1-1.2,0.3-1.6c0.1-0.2,0.5-0.4,0.7-0.5c0.3-0.1,0.7-0.1,1,0 c0.2,0,0.6,0.3,0.7,0.5c0.2,0.3,0.3,1.3,0.4,1.7c0,0.1,0.1-0.4,0.3-0.7c0.4-0.6,1.8-0.8,1.9,0.6c0,0.7,0,0.6,0,1.1 c0,0.5,0,0.8,0,1.2c0,0.4-0.1,1.3-0.2,1.7c-0.1,0.3-0.4,1-0.7,1.4c0,0-1.1,1.2-1.2,1.8c-0.1,0.6-0.1,0.6-0.1,1 c0,0.4,0.1,0.9,0.1,0.9s-0.8,0.1-1.2,0c-0.4-0.1-0.9-0.8-1-1.1c-0.2-0.3-0.5-0.3-0.7,0c-0.2,0.4-0.7,1.1-1.1,1.1 c-0.7,0.1-2.1,0-3.1,0c0,0,0.2-1-0.2-1.4c-0.3-0.3-0.8-0.8-1.1-1.1l-0.8-0.9c-0.3-0.4-1-0.9-1.2-2c-0.2-0.9-0.2-1.4,0-1.8 C2.3,8,2.7,7.8,2.9,7.8c0.2,0,0.7,0,0.9,0.1C4,8,4.1,8,4.3,8.2c0.2,0.3,0.3,0.5,0.2,0.1c-0.1-0.3-0.3-0.6-0.4-1 C3.9,7,3.7,6.5,3.7,5.9C3.7,5.6,3.8,5.1,4.5,4.8z")
(def grab-path "M4.5,8.4 C4.4,8,4.3,7.5,4.1,6.8C3.9,6.3,3.7,6,3.6,5.6C3.5,5.1,3.3,4.9,3.1,4.4C3,4.1,2.8,3.4,2.7,3C2.5,2.5,2.7,2,2.9,1.8 c0.3-0.3,1-0.5,1.4-0.4C4.6,1.5,5,1.9,5.2,2.2c0.3,0.5,0.4,0.6,0.7,1.5c0.4,1,0.6,1.9,0.6,2.2l0.1,0.5c0,0,0-1.1,0-1.2 c0-1-0.1-1.8,0-2.9c0-0.1,0.1-0.6,0.1-0.7c0.1-0.5,0.3-0.8,0.7-1c0.4-0.2,0.9-0.2,1.4,0c0.4,0.2,0.6,0.5,0.7,1c0,0.1,0.1,1,0.1,1.1 c0,1,0,1.6,0,2.2c0,0.2,0,1.6,0,1.5c0.1-0.7,0.1-3.2,0.3-3.9C10,2,10.2,1.7,10.6,1.5C11,1.3,11.7,1.4,12,1.7 c0.3,0.3,0.4,0.7,0.5,1.2c0,0.4,0,0.9,0,1.2c0,0.9,0,1.3,0,2.1c0,0,0,0.3,0,0.2c0.1-0.3,0.2-0.5,0.3-0.7c0-0.1,0.2-0.6,0.4-0.9 c0.1-0.2,0.2-0.4,0.4-0.7c0.2-0.3,0.4-0.4,0.7-0.6c0.5-0.2,1.1,0.1,1.3,0.6c0.1,0.2,0,0.7,0,1.1c-0.1,0.6-0.3,1.3-0.4,1.6 c-0.1,0.4-0.3,1.2-0.3,1.6c-0.1,0.4-0.2,1.4-0.4,1.8c-0.1,0.3-0.4,1-0.7,1.4c0,0-1.1,1.2-1.2,1.8c-0.1,0.6-0.1,0.6-0.1,1 c0,0.4,0.1,0.9,0.1,0.9s-0.8,0.1-1.2,0c-0.4-0.1-0.9-0.8-1-1.1c-0.2-0.3-0.5-0.3-0.7,0c-0.2,0.4-0.7,1.1-1.1,1.1 c-0.7,0.1-2.1,0-3.1,0c0,0,0.2-1-0.2-1.4c-0.3-0.3-0.8-0.8-1.1-1.1l-0.8-0.9c-0.3-0.4-0.6-1.1-1.2-2C1.7,9.7,1,9.1,0.7,8.6 c-0.2-0.4-0.3-1-0.2-1.3c0.2-0.6,0.7-0.9,1.4-0.8C2.4,6.5,2.8,6.7,3.1,7c0.2,0.2,0.6,0.5,0.8,0.7C4.1,7.9,4.1,8,4.3,8.2 C4.5,8.6,4.6,8.7,4.5,8.4")

(defn pan-cursor [mouse-down?]
  (dom/div #js {:className " mouse-cursor holo "
                :key "pan"}
    (dom/svg #js {:className " pan-cursor "}
             (dom/path #js {:className " pan-hand " :d (if mouse-down? grabbing-path grab-path)})
             (dom/path #js {:className " pan-line " :d "M11.5,12.5V9.1 M9.5,9.1l0,3.5 M7.5,12.5l0-3.4"}))))

(def grab-cursor (pan-cursor false))
(def grabbing-cursor (pan-cursor true))
(def select-cursor (dom/div #js {:className " mouse-cursor arrow-cursor holo "
                                 :key "arrow"}
                     (common/icon :cursor)))

(defn mouse [app owner]
  (reify
    om/IDisplayName (display-name [_] "Mouse on Canvas")
    om/IRender
    (render [_]
      (let [mouse (cursors/observe-mouse owner)
            keyboard (:keyboard app)]

        (dom/div #js {:className " mouse "
                      :style #js {:top (:y mouse)
                                  :left (:x mouse)}}

          (cond (keyboard/pan-shortcut-active? app)
                (if (:mouse-down app)
                  grabbing-cursor
                  grab-cursor)

                (keyboard/arrow-shortcut-active? app)
                select-cursor

                (not (get-in app state/right-click-learned-path))
                (dom/div (get hints :dial))

                :else nil))))))
