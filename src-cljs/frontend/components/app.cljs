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
  (:require-macros [frontend.utils :refer [html]]))

(def keymap
  (atom nil))

(defn app* [app owner]
  (reify
    om/IDisplayName (display-name [_] "App")
    om/IRender
    (render [_]
      (let [{:keys [cast! handlers]} (om/get-shared owner)
            chat-opened? (get-in app state/chat-opened-path)
            right-click-learned? (get-in app state/right-click-learned-path)]
        (html [:div.app-main
               [:div.app-canvas {:onContextMenu (fn [e]
                                                 (.preventDefault e)
                                                 (.stopPropagation e))}
                (om/build canvas/svg-canvas app)
                (om/build hud/hud app)]
               (om/build chat/menu app)])))))

(defn app [app owner]
  (reify
    om/IRender
    (render [_]
      (if (:navigation-point app)
        (dom/div #js {:id "app" :className "app"}
          (om/build drawing/signup-button {})
          (om/build overlay/main-menu-button (select-in app [state/overlays-path state/main-menu-learned-path]))
          (when (overlay-visible? app)
            (om/build overlay/overlay app))
          (om/build app* app)
          (dom/div #js {:className "app-main-outline"}))

        (html [:div#app])))))
