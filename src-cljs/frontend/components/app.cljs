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
            [frontend.components.overlay :as overlay]
            [frontend.favicon :as favicon]
            [frontend.overlay :refer [overlay-visible?]]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.seq :refer [dissoc-in select-in]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [ankha.core :as ankha])
  (:require-macros [sablono.core :refer (html)]))

(def keymap
  (atom nil))

(defn app* [app owner]
  (reify
    om/IDisplayName (display-name [_] "App*")
    om/IRender
    (render [_]
      (let [{:keys [cast! handlers]} (om/get-shared owner)
            chat-opened? (get-in app state/chat-opened-path)
            right-click-learned? (get-in app state/right-click-learned-path)]
        (html [:div.inner {:on-click (when (overlay-visible? app)
                                       #(cast! :overlay-closed))
                           :class (when (empty? (:frontend-id-state app))
                                    "loading")}
               [:style "#om-app:active{cursor:auto}"]
               (om/build canvas/canvas (dissoc app :mouse))
               (om/build chat/chat (select-in app [state/chat-opened-path
                                                   state/chat-mobile-opened-path
                                                   [:document/id]
                                                   [:sente-id]
                                                   [:client-id]]))
               (when (and (not right-click-learned?) (:mouse app))
                 (om/build canvas/radial-hint (select-in app [[:mouse]
                                                              [:mouse-type]])))
               [:div.mouse-stats
                {:data-mouse (if (:mouse app)
                               (pr-str (select-keys (:mouse app) [:x :y :rx :ry]))
                               "{:x 0, :y 0, :rx 0, :ry 0}")}]])))))

(defn app [app owner]
  (reify
    om/IDisplayName (display-name [_] "App")
    om/IRender
    (render [_]
      (if (:navigation-point app)
        (dom/div #js {:id "app" :className "app"}
          (when (:show-landing? app)
            (om/build landing/landing (select-keys app [:show-landing? :document/id])))

          (when (and (= :document (:navigation-point app))
                     (not (:cust app)))
            (om/build drawing/signup-button {:db/id (:document/id app)}))

          (when (overlay-visible? app)
            (om/build overlay/overlay app))
          (om/build app* app)
          (om/build hud/hud (select-in app [state/chat-opened-path
                                            state/overlays-path
                                            state/main-menu-learned-path
                                            state/chat-button-learned-path
                                            state/browser-settings-path
                                            [:document/id]
                                            [:subscribers]
                                            [:show-viewers?]
                                            [:client-id]
                                            [:cust]
                                            [:mouse-type]])))
        (html [:div#app])))))
