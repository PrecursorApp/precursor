(ns frontend.components.app
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.string :as str]
            [frontend.async :refer [put!]]
            [frontend.components.aside :as aside]
            [frontend.components.inspector :as inspector]
            [frontend.components.key-queue :as keyq]
            [frontend.components.canvas :as canvas]
            [frontend.components.common :as common]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.seq :refer [dissoc-in]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [ankha.core :as ankha])
  (:require-macros [frontend.utils :refer [html]]))

(def tools-templates
  {:circle {:type :tool-circle
            :path "M302.9,128C292,109.1,276.2,92.8,256,81.1S213.8,64,192,64l0,128 L302.9,128z"}
   :rect   {:type :tool-rect
            :path "M302.9,256c10.9-18.8,17.1-40.7,17.1-64s-6.2-45.2-17.1-64L192,192 L302.9,256z"}
   :line   {:type :tool-line
            :path "M192,320c21.8,0,43.8-5.5,64-17.2s36-28,46.9-46.8L192,192L192,320z"}
   :pen    {:type :tool-pen
            :path "M81.1,256c10.9,18.8,26.7,35.2,46.9,46.8s42.2,17.2,64,17.2l0-128 L81.1,256z"}
   :text   {:type :tool-text
            :path "M81.1,128C70.2,146.8,64,168.7,64,192s6.2,45.2,17.1,64L192,192 L81.1,128z"}
   :select {:type :tool-select
            :path "M192,64c-21.8,0-43.8,5.5-64,17.2s-36,28-46.9,46.8L192,192L192,64z"}})

(def keymap
  (atom nil))

(defn app* [app owner]
  (reify
    om/IDisplayName (display-name [_] "App")
    om/IRender
    (render [_]
      (if-not (:navigation-point app)
        (html [:div#app])

        (let [controls-ch (om/get-shared owner [:comms :controls])
              persist-state! #(put! controls-ch [:state-persisted])
              restore-state! #(put! controls-ch [:state-restored])
              logged-in? (get-in app state/user-path)]
          (reset! keymap {["ctrl+s"] persist-state!
                          ["ctrl+r"] restore-state!})
          (html
           [:div#app
            (om/build keyq/keyboard-handler app
                      {:opts {:keymap keymap
                              :error-ch (get-in app [:comms :errors])}})]))))))


(defn app [app owner]
  (reify
    om/IRender
    (render [_]
      (let [{:keys [cast! handlers]}      (om/get-shared owner)
            show-grid?           (get-in app state/show-grid-path)
            night-mode?          (get-in app state/night-mode-path)
            aside-opened?        (get-in app state/aside-menu-opened-path)]
        (html [:div#app {:class (str/join " " (concat (when show-grid? ["show-grid"])
                                                      (when night-mode? ["night-mode"])))}
               [:aside.app-aside {:class (when aside-opened? "hover")
                                  :on-mouse-enter #(cast! :aside-menu-opened)
                                  :on-mouse-leave #(cast! :aside-menu-closed)
                                  :on-touch-start #(cast! :aside-menu-opened)}
                [:div.aside-toggles
                 [:button (common/icon :logomark-precursor)]]
                (om/build aside/menu app)]
               [:main.app-main {:on-touch-start #(cast! :aside-menu-closed)
                                :onContextMenu (fn [e]
                                                 (.preventDefault e)
                                                 (.stopPropagation e))}
                (om/build canvas/svg-canvas app)
                (when (:mouse app)
                  [:div.mouse-stats
                   (pr-str (:mouse app))])
                (when (get-in app [:menu :open?])
                  [:div.radial-menu {:style {:top  (- (get-in app [:menu :y]) 192)
                                             :left (- (get-in app [:menu :x]) 192)}}
                   [:svg {:width "384" :height "384"}
                    (for [[tool template] tools-templates]
                      [:path.radial-button {:d (:path template)
                                            :on-mouse-up #(do (cast! :tool-selected [tool]))
                                            :on-touch-end #(do (cast! :tool-selected [tool]))}])]
                   (for [[tool template] tools-templates]
                     [:div.radial-tool-type
                      (common/icon (:type template))
                      [:span (name tool)]])
                   [:div.radial-menu-nub]])
                [:div.right-click-menu
                 [:button "Cut"]
                 [:button "Copy"]
                 [:button "Paste"]
                 [:hr]
                 [:button "Align"]
                 [:button "Transform"]
                 [:button "Distribute"]
                 [:hr]
                 [:button "Lock"]
                 [:button "Group"]
                 [:button "Arrange"]
                 [:div.right-click-align
                  [:button "test"]
                  [:button "test"]
                  [:button "test"]]]]])))))
