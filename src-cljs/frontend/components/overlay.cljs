(ns frontend.components.overlay
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datascript :as d]
            [frontend.analytics :as analytics]
            [frontend.async :refer [put!]]
            [frontend.auth :as auth]
            [frontend.components.common :as common]
            [frontend.datascript :as ds]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]])
  (:import [goog.ui IdGenerator]))

(defn info [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
          [:div.menu-prompt {:class (str "menu-prompt-" "info")}
           [:div.menu-header
            [:a.menu-close {:on-click #(cast! :overlay-closed)
                            :role "button"}
             (common/icon :times)]]
           [:div.menu-prompt-body
            [:h2 "What is Precursor?"]
            [:p
             "No-nonsense prototyping—"
             "Perfect for wireframing, sketching, and brainstorming. "
             [:a {:on-click #(cast! :invite-link-clicked)
                  :role "button"
                  :title "In chat, type \"/invite their@email.com\""}
              "Invite"]
             " a teammate and collaborate instantly. "
             " We just got started, so if you have feedback say "
             [:a {:href "mailto:hi@prcrsr.com?Subject=I%20have%20feedback"
                  :title "We love feedback, good or bad."}
              "hi@prcrsr.com"]]
            [:a.prompt-button {:on-click #(cast! :overlay-info-toggled)
                               :role "button"}
             "Okay"]]
           [:div.menu-footer
            [:p
             [:a.menu-footer-link {:href "https://twitter.com/prcrsr_app"
                                   :on-click #(analytics/track "Twitter link clicked" {:location "info overlay"})
                                   :title "On Twitter"
                                   :target "_blank"}
              "Tell us what to add next."]]
            (common/mixpanel-badge)]])))))

(defn shortcuts [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
          [:div.menu-view {:class (str "menu-view-" "shortcuts")}
           [:div.menu-view-frame
            [:div.shortcuts-item
             [:div.shortcuts-key "S"]
             [:div.shortcuts-result "Select"]]
            [:div.shortcuts-item
             [:div.shortcuts-key "R"]
             [:div.shortcuts-result "Rectangle"]]
            [:div.shortcuts-item
             [:div.shortcuts-key "C"]
             [:div.shortcuts-result "Circle"]]
            [:div.shortcuts-item
             [:div.shortcuts-key "L"]
             [:div.shortcuts-result "Line"]]
            [:div.shortcuts-item
             [:div.shortcuts-key "P"]
             [:div.shortcuts-result "Pen"]]
            [:div.shortcuts-item
             [:div.shortcuts-key "T"]
             [:div.shortcuts-result "Text"]]
            [:div.shortcuts-item
             [:div.shortcuts-key "1"]
             [:div.shortcuts-result "Snap to origin"]]
            [:div.shortcuts-item
             [:div.shortcuts-key "Cmd"]
             [:div.shortcuts-key "Z"]
            [:div.shortcuts-result "Undo"]]]])))))

(defn username [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:div.menu-prompt {:class (str "menu-prompt-" "username")}
          [:div.menu-header
           [:a.menu-close {:on-click #(cast! :overlay-closed)
                           :role "button"}
            (common/icon :times)]]
          [:div.menu-prompt-body
           [:h2 "Let's change that name."]
           [:p
            "Help your team communicate faster with each other by using custom names. "
            "Log in or sign up to change how your name appears in chat."]
           [:a.prompt-button {:href (auth/auth-url)
                              :role "button"}
            "Sign Up"]]
          [:div.menu-footer
           [:a.menu-footer-link {:href "#"}
            "No thanks."]]])))))

(def overlay-components
  {:info info
   :shortcuts shortcuts
   :username username})

(defn overlay [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            overlay-component (get overlay-components (:overlay app) info)]
        (html
          [:div.app-overlay {:on-click #(cast! :overlay-closed)}
           [:div.app-overlay-background]

           ; [:aside.app-overlay-menu {:on-click #(.stopPropagation %)}
           ;  [:div.menu-header
           ;   [:a.menu-back {:on-click #(cast! :overlay-closed)
           ;                  :role "button"}
           ;    (common/icon :arrow-left)]
           ;   [:div.menu-title
           ;    [:h3 "Shortcuts"]]]
           ;  [:div.menu-body
           ;   (om/build overlay-component app)]]

           [:aside.app-overlay-menu {:on-click #(.stopPropagation %)}
            (om/build overlay-component app)]

           ])))))
