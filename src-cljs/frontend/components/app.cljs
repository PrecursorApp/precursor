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
            [frontend.cursors :as cursors]
            [frontend.favicon :as favicon]
            [frontend.keyboard :as keyboard]
            [frontend.overlay]
            [frontend.state :as state]
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
            right-click-learned? (get-in app state/right-click-learned-path)
            ]

        (if-let [nav-point (:navigation-point app)]
          (html
           [:div#app.app {:class (str (frontend.overlay/app-overlay-class app)
                                      (when (:show-landing? app) " state-outer ")
                                      (if chat-opened? " chat-opened " " chat-closed "))}
            (om/build text-sizer {})
            (om/build progress/progress-bar {} {:react-key "progress-bar"})

            (when (:show-landing? app)
              (om/build outer/outer (select-in app [[:show-landing?]
                                                    [:document/id]
                                                    [:navigation-point]
                                                    [:navigation-data]
                                                    [:cust]
                                                    [:subscribers :info]
                                                    [:page-count]
                                                    [:show-scroll-to-arrow]])
                        {:react-key "outer"}))

            (when (and (keyword-identical? :document nav-point)
                       (empty? (:cust app))
                       (seq (:frontend-id-state app)))
              (om/build drawing/signup-button {:db/id (:document/id app)}
                        {:react-key "signup-animation"}))

            (when overlay-visible?
              (om/build overlay/overlay app {:react-key "overlay"}))

            [:div.inner {:on-click (when overlay-visible?
                                     #(cast! :overlay-closed))
                         :class (when (empty? (:frontend-id-state app)) "loading")
                         :key "inner"}
             [:style "#om-app:active{cursor:auto}"]
             (om/build canvas/canvas (select-in app (concat [state/current-tool-path
                                                             [:drawing :in-progress?]
                                                             [:drawing :relation-in-progress?]
                                                             [:mouse-down]
                                                             [:layer-properties-menu]
                                                             [:menu]
                                                             [:client-id]
                                                             [:cust-data]]
                                                            (keyboard/arrow-shortcut-state-keys app)))
                       {:react-key "canvas"})

             (om/build chat/chat (select-in app [state/chat-opened-path
                                                 state/chat-mobile-opened-path
                                                 state/chat-submit-learned-path
                                                 [:document/id]
                                                 [:sente-id]
                                                 [:client-id]
                                                 [:show-landing?]
                                                 [:cust-data]
                                                 [:navigation-data]])
                       {:react-key "chat"})

             (when (not right-click-learned?)
               (om/build canvas/radial-hint (select-in app [[:mouse-type]])
                         {:react-key "radial-hint"}))]

            (om/build hud/hud (select-in app [state/chat-opened-path
                                              state/overlays-path
                                              state/main-menu-learned-path
                                              state/chat-button-learned-path
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
                      {:react-key "hud"})])
          (html [:div#app]))))))

(defn app [app owner]
  (reify
    om/IDisplayName (display-name [_] "App")
    om/IRender
    (render [_]
      (om/build app* (-> app
                       (dissoc :mouse :progress)
                       (dissoc-in [:subscribers :mice])
                       (dissoc-in [:subscribers :layers]))
                {:react-key "app*"}))))
