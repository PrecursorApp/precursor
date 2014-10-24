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
              show-inspector? (get-in app state/show-inspector-path)
              logged-in? (get-in app state/user-path)]
          (reset! keymap {["ctrl+s"] persist-state!
                          ["ctrl+r"] restore-state!})
          (html
           [:div#app
            (om/build keyq/KeyboardHandler app
                      {:opts {:keymap keymap
                              :error-ch (get-in app [:comms :errors])}})
            (when show-inspector?
              ;; TODO inspector still needs lots of work. It's slow and it defaults to
              ;;     expanding all datastructures.
              (om/build inspector/inspector app))]))))))


(defn app [app owner]
  (reify
    om/IInitState
    (init-state [_]
      {:hovered-tool nil
       :hovered-tool-select nil
       :hovered-tool-shape nil
       :hovered-tool-line nil
       :aside-hover nil})
    om/IRender
    (render [_]
      (let [{:keys [cast!]}      (om/get-shared owner)
            show-grid?           (get-in app state/show-grid-path)
            night-mode?          (get-in app state/night-mode-path)
            hovered-tool?        (:hovered-tool (om/get-state owner))
            hovered-tool-select? (:hovered-tool-select (om/get-state owner))
            hovered-tool-shape?  (:hovered-tool-shape (om/get-state owner))
            hovered-tool-line?   (:hovered-tool-line (om/get-state owner))
            hovered-aside?       (:hovered-aside (om/get-state owner))]
        (html [:div#app {:class (str/join " " (concat (when show-grid? ["show-grid"])
                                                      (when night-mode? ["night-mode"])))}
               [:aside.app-aside {:class (when hovered-aside? "hover")
                                  :on-mouse-enter #(om/set-state! owner :hovered-aside true)
                                  :on-mouse-leave #(om/set-state! owner :hovered-aside false)
                                  :on-touch-start #(om/set-state! owner :hovered-aside true)}
                 (om/build aside/menu app)]
               [:main.app-main {:on-touch-start #(om/set-state! owner :hovered-aside false)
                                :onContextMenu (fn [e]
                                                 (.preventDefault e)
                                                 (.stopPropagation e))}
                 (om/build canvas/svg-canvas app)
                 (when (get-in app [:menu :open?])
                   [:div.radial-menu {:style {:top  (- (get-in app [:menu :y]) 128)
                                              :left (- (get-in app [:menu :x]) 128)}
                                      :on-mouse-leave #(cast! :menu-closed)}
                    [:button {:on-mouse-up #(do (cast! :tool-selected [:text])
                                                (om/set-state! owner :hovered-tool false))
                              :on-mouse-enter #(om/set-state! owner :hovered-tool true)
                              :on-mouse-leave #(om/set-state! owner :hovered-tool false)
                              :class (when hovered-tool? "hover")}
                     [:object
                      (common/icon :tool-text)
                      [:span "Text"]]]
                    [:button {:on-mouse-up  #(do (cast! :tool-selected [:select])
                                                 (om/set-state! owner :hovered-tool-select false))
                              :on-mouse-enter #(om/set-state! owner :hovered-tool-select true)
                              :on-mouse-leave #(om/set-state! owner :hovered-tool-select false)
                              :class (when hovered-tool-select? "hover")}
                     [:object
                      (common/icon :cursor)
                      [:span "Select"]]]
                    [:button {:on-mouse-up #(do (cast! :tool-selected [:shape])
                                                (om/set-state! owner :hovered-tool-shape false))
                              :on-mouse-enter #(om/set-state! owner :hovered-tool-shape true)
                              :on-mouse-leave #(om/set-state! owner :hovered-tool-shape false)
                              :class (when hovered-tool-shape? "hover")}
                     [:object
                      (common/icon :tool-square)
                      [:span "Shape"]]]
                    [:button {:on-mouse-up #(do (cast! :tool-selected [:line])
                                                (om/set-state! owner :hovered-tool-line false))
                              :on-mouse-enter #(om/set-state! owner :hovered-tool-line true)
                              :on-mouse-leave #(om/set-state! owner :hovered-tool-line false)
                              :class (when hovered-tool-line? "hover")}
                     [:object
                      (common/icon :tool-line)
                      [:span "Line"]]]
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
