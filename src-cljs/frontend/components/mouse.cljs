(ns frontend.components.mouse
  (:require [frontend.components.common :as common]
            [frontend.components.canvas :as canvas]
            [frontend.cursors :as cursors]
            [frontend.keyboard :as keyboard]
            [frontend.state :as state]
            [frontend.utils :as utils]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [sablono.core :refer (html)]))

(def tools-templates
  {:circle {:type "ellipse"
            :path "M128,0v128l110.9-64C216.7,25.7,175.4,0,128,0z"
            :hint "Ellipse Tool (L)"
            :icon :stroke-ellipse}
   :rect   {:type "rectangle"
            :path "M238.9,192c10.9-18.8,17.1-40.7,17.1-64s-6.2-45.2-17.1-64 L128,128L238.9,192z"
            :hint "Rectangle Tool (M)"
            :icon :stroke-rectangle}
   :line   {:type "line"
            :path "M238.9,192L128,128v128C175.4,256,216.7,230.3,238.9,192z"
            :hint "Line Tool (\\)"
            :icon :stroke-line}
   :pen    {:type "pencil"
            :path "M17.1,192c22.1,38.3,63.5,64,110.9,64V128L17.1,192z"
            :hint "Pencil Tool (N)"
            :icon :stroke-pencil}
   :text   {:type "text"
            :path "M17.1,64C6.2,82.8,0,104.7,0,128s6.2,45.2,17.1,64L128,128 L17.1,64z"
            :hint "Text Tool (T)"
            :icon :stroke-text}
   :select {:type "select"
            :path "M128,0C80.6,0,39.3,25.7,17.1,64L128,128V0z"
            :hint "Select Tool (V)"
            :icon :stroke-cursor}})

(defn radial-menu [app owner]
  (reify
    om/IDisplayName (display-name [_] "Radial Menu")
    om/IRender
    (render [_]
      (let [{:keys [cast! handlers]} (om/get-shared owner)]
        (html
         [:a.radial-menu {:style {:top  (- (get-in app [:radial :y]) 128)
                                  :left (- (get-in app [:radial :x]) 128)}}
          [:svg.radial-buttons {:width "256" :height "256"}
           (for [[tool template] tools-templates]
             (html
              [:g.radial-button {:class (str "tool-" (:type template))}
               [:title (:hint template)]
               [:path.radial-pie {:d (:path template)
                                  :key tool
                                  :on-mouse-up #(do (cast! :tool-selected [tool]))
                                  :on-touch-end #(do (cast! :tool-selected [tool]))}]
               [:path.radial-icon {:class (str "shape-" (:type template))
                                   :d (get common/icon-paths (:icon template))}]]))
           [:circle.radial-point {:cx "128" :cy "128" :r "4"}]]])))))

(defn mouse [app owner]
  (reify
    om/IRender
    (render [_]
      (let [pan (cursors/observe-pan-tool owner)
            mouse (cursors/observe-mouse owner)
            mouse-down? (:mouse-down app)
            keyboard (:keyboard app)
            panning? (keyboard/pan-shortcut-active? app)
            right-click-learned? (get-in app state/right-click-learned-path)
            radial-open? (get-in app [:radial :open?])]
        (html
          [:div.mouse {:style {:top (:y mouse) :left (:x mouse)}}

           (when panning?
             [:div.mouse-cursor.holo
              [:svg.pan-handle
               [:path.pan-hand {:d (if mouse-down? "M4.5,4.8 C5,4.6,5.9,4.8,6.2,5.3c0.2,0.5,0.4,1.2,0.4,1.1c0-0.4,0-1.2,0.1-1.6c0.1-0.3,0.3-0.6,0.7-0.7C7.7,4,8,4,8.3,4 C8.6,4.1,9,4.3,9.1,4.5c0.4,0.6,0.4,1.9,0.4,1.8c0.1-0.3,0.1-1.2,0.3-1.6c0.1-0.2,0.5-0.4,0.7-0.5c0.3-0.1,0.7-0.1,1,0 c0.2,0,0.6,0.3,0.7,0.5c0.2,0.3,0.3,1.3,0.4,1.7c0,0.1,0.1-0.4,0.3-0.7c0.4-0.6,1.8-0.8,1.9,0.6c0,0.7,0,0.6,0,1.1 c0,0.5,0,0.8,0,1.2c0,0.4-0.1,1.3-0.2,1.7c-0.1,0.3-0.4,1-0.7,1.4c0,0-1.1,1.2-1.2,1.8c-0.1,0.6-0.1,0.6-0.1,1 c0,0.4,0.1,0.9,0.1,0.9s-0.8,0.1-1.2,0c-0.4-0.1-0.9-0.8-1-1.1c-0.2-0.3-0.5-0.3-0.7,0c-0.2,0.4-0.7,1.1-1.1,1.1 c-0.7,0.1-2.1,0-3.1,0c0,0,0.2-1-0.2-1.4c-0.3-0.3-0.8-0.8-1.1-1.1l-0.8-0.9c-0.3-0.4-1-0.9-1.2-2c-0.2-0.9-0.2-1.4,0-1.8 C2.3,8,2.7,7.8,2.9,7.8c0.2,0,0.7,0,0.9,0.1C4,8,4.1,8,4.3,8.2c0.2,0.3,0.3,0.5,0.2,0.1c-0.1-0.3-0.3-0.6-0.4-1 C3.9,7,3.7,6.5,3.7,5.9C3.7,5.6,3.8,5.1,4.5,4.8z"
                                                   "M4.5,8.4 C4.4,8,4.3,7.5,4.1,6.8C3.9,6.3,3.7,6,3.6,5.6C3.5,5.1,3.3,4.9,3.1,4.4C3,4.1,2.8,3.4,2.7,3C2.5,2.5,2.7,2,2.9,1.8 c0.3-0.3,1-0.5,1.4-0.4C4.6,1.5,5,1.9,5.2,2.2c0.3,0.5,0.4,0.6,0.7,1.5c0.4,1,0.6,1.9,0.6,2.2l0.1,0.5c0,0,0-1.1,0-1.2 c0-1-0.1-1.8,0-2.9c0-0.1,0.1-0.6,0.1-0.7c0.1-0.5,0.3-0.8,0.7-1c0.4-0.2,0.9-0.2,1.4,0c0.4,0.2,0.6,0.5,0.7,1c0,0.1,0.1,1,0.1,1.1 c0,1,0,1.6,0,2.2c0,0.2,0,1.6,0,1.5c0.1-0.7,0.1-3.2,0.3-3.9C10,2,10.2,1.7,10.6,1.5C11,1.3,11.7,1.4,12,1.7 c0.3,0.3,0.4,0.7,0.5,1.2c0,0.4,0,0.9,0,1.2c0,0.9,0,1.3,0,2.1c0,0,0,0.3,0,0.2c0.1-0.3,0.2-0.5,0.3-0.7c0-0.1,0.2-0.6,0.4-0.9 c0.1-0.2,0.2-0.4,0.4-0.7c0.2-0.3,0.4-0.4,0.7-0.6c0.5-0.2,1.1,0.1,1.3,0.6c0.1,0.2,0,0.7,0,1.1c-0.1,0.6-0.3,1.3-0.4,1.6 c-0.1,0.4-0.3,1.2-0.3,1.6c-0.1,0.4-0.2,1.4-0.4,1.8c-0.1,0.3-0.4,1-0.7,1.4c0,0-1.1,1.2-1.2,1.8c-0.1,0.6-0.1,0.6-0.1,1 c0,0.4,0.1,0.9,0.1,0.9s-0.8,0.1-1.2,0c-0.4-0.1-0.9-0.8-1-1.1c-0.2-0.3-0.5-0.3-0.7,0c-0.2,0.4-0.7,1.1-1.1,1.1 c-0.7,0.1-2.1,0-3.1,0c0,0,0.2-1-0.2-1.4c-0.3-0.3-0.8-0.8-1.1-1.1l-0.8-0.9c-0.3-0.4-0.6-1.1-1.2-2C1.7,9.7,1,9.1,0.7,8.6 c-0.2-0.4-0.3-1-0.2-1.3c0.2-0.6,0.7-0.9,1.4-0.8C2.4,6.5,2.8,6.7,3.1,7c0.2,0.2,0.6,0.5,0.8,0.7C4.1,7.9,4.1,8,4.3,8.2 C4.5,8.6,4.6,8.7,4.5,8.4")}]
               [:path.pan-lines {:d "M11.5,12.5V9.1 M9.5,9.1l0,3.5 M7.5,12.5l0-3.4"}]]])

           (when radial-open?
             (om/build radial-menu (utils/select-in app [[:radial]])))

           (let [hint-chat {:data-chat "Open chat." :class "east"}
                 hint-menu {:data-menu "Open menu." :class "west"}
                 hint-name {:data-name "Change your name." :class "west"}
                 hint-team {:data-team "Open team menu." :class "east"}
                 hint-radial {:data-radial "Right-click." :class "north fast"}]
             ;; TODO find solution for hold+hold hint
             [:div.hint.holo (if radial-open?
                               hint-chat
                               hint-radial)])

           ])))))
