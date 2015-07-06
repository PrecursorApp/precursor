(ns frontend.components.app
  (:require [cemerick.url :as url]
            [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.set :as set]
            [clojure.string :as str]
            [frontend.analytics :as analytics]
            [frontend.async :refer [put!]]
            [frontend.auth :as auth]
            [frontend.components.chat :as chat]
            [frontend.components.inspector :as inspector]
            [frontend.components.hud :as hud]
            [frontend.components.key-queue :as keyq]
            [frontend.components.canvas :as canvas]
            [frontend.components.common :as common]
            [frontend.components.landing :as landing]
            [frontend.components.drawing :as drawing]
            [frontend.components.outer :as outer]
            [frontend.components.overlay :as overlay]
            [frontend.components.progress :as progress]
            [frontend.components.rtc :as rtc]
            [frontend.cursors :as cursors]
            [frontend.favicon :as favicon]
            [frontend.keyboard :as keyboard]
            [frontend.overlay]
            [frontend.state :as state]
            [frontend.urls :as urls]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.seq :refer [dissoc-in select-in]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [ankha.core :as ankha])
  (:require-macros [sablono.core :refer (html)]))

(def keymap
  (atom nil))

(defn text-sizer [app owner]
  (reify
    om/IDisplayName (display-name [_] "Text sizer")
    om/IRender
    (render [_]
      (dom/canvas #js {:id "text-sizer"
                       :width 0
                       :height 0}))))

(defn app* [app owner]
  (reify
    om/IDisplayName (display-name [_] "App*")
    om/IRender
    (render [_]
      (let [{:keys [cast! handlers]} (om/get-shared owner)
            chat-opened? (get-in app state/chat-opened-path)
            overlay-visible? (frontend.overlay/overlay-visible? app)
            right-click-learned? (get-in app state/right-click-learned-path)]

        (if-let [nav-point (:navigation-point app)]
          (html
           [:div#app.app {:class (str (frontend.overlay/app-overlay-class app)
                                      (when-not (or (:show-landing? app) overlay-visible?) " state-inner ")
                                      (when (:show-landing? app) " state-outer ")
                                      (if chat-opened? " chat-opened " " chat-closed ")
                                      (when (keyboard/pan-shortcut-active? app) " state-pan ")
                                      (when (and (not= (:navigation-point app) :issues-list)
                                                 (<= (:page-count app) 1))
                                        " entry ")
                                      (when (:outer-to-inner? app) " outer-to-inner ")
                                      (when (:menu-to-inner? app) " menu-to-inner "))}
            (om/build text-sizer {})

            (when (and (keyword-identical? :document nav-point)
                       (empty? (:cust app))
                       (seq (:frontend-id-state app)))
              (om/build drawing/signup-button {:document {:db/id (:document/id app)}
                                               :camera (:camera app)}
                        {:react-key "signup-animation"}))

            (om/build overlay/overlay app {:react-key "overlay"})

            [:div.inner (merge {:class (when (empty? (:frontend-id-state app)) "loading")
                                :key "inner"})
             [:style "#om-app:active{cursor:auto}"]
             [:div.background {:key "inner-background"}]
             (om/build canvas/canvas (select-in app [state/chat-opened-path
                                                     state/current-tool-path
                                                     state/right-click-learned-path
                                                     [:drawing :in-progress?]
                                                     [:drawing :relation-in-progress?]
                                                     [:mouse-down]
                                                     [:layer-properties-menu]
                                                     [:radial]
                                                     [:client-id]
                                                     [:cust-data]
                                                     [:document/id]
                                                     [:keyboard]
                                                     [:keyboard-shortcuts]])
                       {:react-key "canvas"})

             (om/build chat/chat (select-in app [state/chat-opened-path
                                                 state/chat-mobile-opened-path
                                                 state/chat-submit-learned-path
                                                 [:document/id]
                                                 [:sente-id]
                                                 [:client-id]
                                                 [:show-landing?]
                                                 [:cust-data]
                                                 [:chat]
                                                 [:navigation-data]])
                       {:react-key "chat"})
             (when overlay-visible?
               [:div.foreground {:on-click #(cast! :overlay-escape-clicked)
                                 :on-mouse-enter #(cast! :navigate-to-landing-doc-hovered)}
                [:div.border.border-top]
                [:div.border.border-bottom]
                [:div.border.border-left]
                [:div.border.border-right]])]

            (om/build hud/hud (select-in app [state/chat-opened-path
                                              state/viewers-opened-path
                                              state/overlays-path
                                              state/main-menu-learned-path
                                              state/chat-button-learned-path
                                              state/welcome-info-learned-path
                                              state/browser-settings-path
                                              [:document/id]
                                              [:subscribers :info]
                                              [:show-viewers?]
                                              [:client-id]
                                              [:cust]
                                              [:mouse-type]
                                              [:cust-data]
                                              [:team]
                                              [:max-document-scope]
                                              (state/doc-tx-rejected-count-path (:document/id app))])
                      {:react-key "hud"})

            (when (:show-landing? app)
              (om/build outer/outer (select-in app [[:show-landing?]
                                                    [:document/id]
                                                    [:navigation-point]
                                                    [:navigation-data]
                                                    [:cust]
                                                    [:subscribers :info]
                                                    [:page-count]
                                                    [:show-scroll-to-arrow]
                                                    state/browser-settings-path])
                        {:react-key "outer"}))

            (om/build rtc/rtc (select-in app [[:subscribers :info]])
                      {:react-key "rtc"})

            (om/build progress/progress {} {:react-key "progress-bar"})])
          (html [:div#app]))))))

(defn app [app owner]
  (reify
    om/IDisplayName (display-name [_] "App")
    om/IRender
    (render [_]
      (om/build app* (-> app
                       (dissoc :mouse :progress :pan)
                       (dissoc-in [:subscribers :mice])
                       (dissoc-in [:subscribers :layers]))
                {:react-key "app*"}))))
