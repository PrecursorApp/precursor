(ns frontend.components.context
  (:require [frontend.components.common :as common]
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

(defn context [app owner]
  (reify
    om/IDisplayName (display-name [_] "Mouse on Canvas")
    om/IRender
    (render [_]
      (dom/div #js {:className " context "}
        (when (get-in app [:radial :open?])
          (om/build radial-menu (utils/select-in app [[:radial]])))))))
