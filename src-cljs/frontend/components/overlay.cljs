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
          [:figure.overlay-info {:on-click #(cast! :overlay-info-toggled)}
           [:div.overlay-background]
           [:a.overlay-close {:role "button"}
            (common/icon :times)]
           [:article {:on-click #(.stopPropagation %)}
            [:h1 "What's this?"]
            [:p
             "Precursor is a no-nonsense prototyping tool—"
             "use it for sketching, rapid prototyping, and team brainstorming. "
             [:a {:on-click #(cast! :invite-link-clicked)
                  :role "button"
                  :title "In chat, type \"/invite their@email.com\""}
              "Invite your team"]
             " and everyone can collaborate in the same document, instantly. "
             " We're still pretty new, so if you have feedback or a great idea sketch it up and ping "
             [:a {:on-click #(cast! :chat-link-clicked)
                  :role "button"
                  :title "Start any chat with \"@prcrsr\" and we'll see it."}
              "@prcrsr"]
             " in the chat, or say "
             [:a {:href "mailto:hi@prcrsr.com?Subject=I%20have%20feedback"
                  :title "We love feedback, good or bad."}
              "hi@prcrsr.com"]]
            [:div.info-buttons
             [:button.info-okay {:on-click #(cast! :overlay-info-toggled)}
              "Okay, sounds good."]
             ;; TODO ab test this ".info-twitter" link with the the current one below -dk
             ;; [:a.info-twitter {:href "https://twitter.com/prcrsr_app"
             ;;                   :on-click #(analytics/track "Twitter link clicked" {:location "info overlay"})
             ;;                   :data-top "Tell us what to add next."
             ;;                   :target "_blank"}
             ;;   (common/icon :twitter)]
             [:p [:a.info-twitter {:href "https://twitter.com/prcrsr_app"
                                   :on-click #(analytics/track "Twitter link clicked" {:location "info overlay"})
                                   :title "On Twitter"
                                   :target "_blank"}
               "Tell us what to add next."]]]]
           (common/mixpanel-badge)])))))

(defn shortcuts [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
          [:div.options-view {:class (str "option-view-" "shortcuts")}
           [:div.option-frame
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
          [:figure.overlay-change-name {:on-click #(cast! :overlay-closed)}
           [:div.overlay-background]
           [:a.overlay-close {:role "button"}
            (common/icon :times)]
           [:article {:on-click #(.stopPropagation %)}
            [:h2 "Let's change that name."]
            [:p
             "Help your team communicate faster with each other by using custom names. "
             "Log in or sign up to change how your name appears in chat."]
            [:div.info-buttons
             [:a.info-okay {:href (auth/auth-url)
                            :role "button"}
              "Sign Up"]
             [:a.info-twitter {:on-click #(cast! :overlay-closed)
                               :role "button"}
              "No thanks."]]]])))))

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
           [:aside.app-overlay-options {:on-click #(.stopPropagation %)}
            [:div.options-header
             [:a.options-back {:on-click #(cast! :overlay-closed)
                               :role "button"}
              (common/icon :arrow-left)]
             [:div.option-title
              [:h3 "Shortcuts"]]
             ; [:a.options-close {:on-click #(cast! :overlay-closed)
             ;                    :role "button"}
             ;  (common/icon :times)]
             ]
            [:div.options-body
             (om/build overlay-component app)]]])))))
