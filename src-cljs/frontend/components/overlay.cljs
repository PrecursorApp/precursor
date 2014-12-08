(ns frontend.components.overlay
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datascript :as d]
            [frontend.analytics :as analytics]
            [frontend.async :refer [put!]]
            [frontend.auth :as auth]
            [frontend.components.common :as common]
            [frontend.components.doc-viewer :as doc-viewer]
            [frontend.datascript :as ds]
            [frontend.overlay :refer [current-overlay overlay-visible?]]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]])
  (:import [goog.ui IdGenerator]))

(defn main-menu-button [data owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            menu-button-learned? (get-in data state/menu-button-learned-path)] ; TODO figure out if this should be different because we used to call chat menu "the menu"
        (html
          [:a.main-menu-button {:on-click (if (overlay-visible? data)
                                            #(cast! :overlay-menu-closed)
                                            #(cast! :main-menu-opened))
                                :role "button"
                                :class (when (overlay-visible? data)
                                         (if (= :start (current-overlay data))
                                           "close bkg-light"
                                           "back bkg-light"))
                                :data-right (when-not menu-button-learned? "Open Menu")
                                :title (when menu-button-learned? "Open Menu")}
           (common/icon :menu)])))))

(defn start [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
          [:div.menu-view {:class (str "menu-view-" "start")}
           [:div.menu-view-frame
            [:a.menu-item {:role "button"}
             (common/icon :newdoc)
             [:span "New Document"]]
            [:a.menu-item {:on-click #(cast! :your-docs-opened)
                           :role "button"}
             (common/icon :clock)
             [:span "Recent Documents"]]
            [:a.menu-item {:on-click #(cast! :invite-menu-opened)
                           :role "button"}
             (common/icon :users)
             [:span "Invite Collaborators"]]
            [:a.menu-item {:on-click #(cast! :shortcuts-menu-opened)
                           :role "button"}
             (common/icon :command)
             [:span "Shortcuts"]]
            [:a.menu-item {:role "button"}
             (common/icon :logout)
             [:span "Log out"]]]])))))

(defn invite [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
          [:div.menu-view {:class (str "menu-view-" "invite")}
           [:article.menu-view-frame
            [:p "Enter your teammate's email address and and press enter to send them an invite to collaborate with you in your document. Separate multiple emails with a space or a comma."]
            [:form.menu-invite-form
             [:input {:type "text"
                      :required "true"
                      :data-adaptive ""}]
             [:label {:data-placeholder "Teammate's Email"
                      :data-placeholder-nil "What's your teammate's email?"
                      :data-placeholder-forgot "Don't forget to press enter."}]]
            ]])))))

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
             "perfect for wireframing, sketching, and brainstorming. "
             [:a {:on-click #(cast! :invite-link-clicked)
                  :role "button"
                  :title "In chat, type \"/invite their@email.com\""}
              "Invite"]
             " your team and collaborate with them instantly. "
             " We just got started, so if you have feedback say "
             [:a {:href "mailto:hi@prcrsr.com?Subject=I%20have%20feedback"
                  :title "We love feedback, good or bad."}
              "hi@prcrsr.com"]
             " or on "
             [:a {:href "https://twitter.com/prcrsr_app"
                  :on-click #(analytics/track "Twitter link clicked" {:location "info overlay"})
                  :title "On Twitter"
                  :target "_blank"}
              "Twitter"]
             "."]
            [:a.prompt-button {:on-click #(cast! :overlay-info-toggled)
                               :role "button"}
             "Okay"]]
           [:div.menu-footer
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
             [:div.shortcuts-key (common/icon :command)]
             [:div.shortcuts-key "Z"]
            [:div.shortcuts-result "Undo"]]]])))))

; (defn shortcuts [app owner]
;   (reify
;     om/IRender
;     (render [_]
;       (let [cast! (om/get-shared owner :cast!)]
;         (html
;           [:div.menu-prompt {:class (str "menu-prompt-" "shortcuts")}
;            [:div.menu-header
;             [:a.menu-close {:on-click #(cast! :overlay-closed)
;                             :role "button"}
;              (common/icon :times)]]
;            [:div.menu-prompt-body
;             [:h2 "Shortcuts"]
;             [:div.shortcuts-item
;              [:div.shortcuts-key "S"]
;              [:div.shortcuts-result "Select"]]
;             [:div.shortcuts-item
;              [:div.shortcuts-key "R"]
;              [:div.shortcuts-result "Rectangle"]]
;             [:div.shortcuts-item
;              [:div.shortcuts-key "C"]
;              [:div.shortcuts-result "Circle"]]
;             [:div.shortcuts-item
;              [:div.shortcuts-key "L"]
;              [:div.shortcuts-result "Line"]]
;             [:div.shortcuts-item
;              [:div.shortcuts-key "P"]
;              [:div.shortcuts-result "Pen"]]
;             [:div.shortcuts-item
;              [:div.shortcuts-key "T"]
;              [:div.shortcuts-result "Text"]]
;             [:div.shortcuts-item
;              [:div.shortcuts-key "1"]
;              [:div.shortcuts-result "Snap to origin"]]
;             [:div.shortcuts-item
;              [:div.shortcuts-key "Cmd"]
;              [:div.shortcuts-key "Z"]
;             [:div.shortcuts-result "Undo"]]]])))))

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
                              :on-click #(do
                                           (.preventDefault %)
                                           (cast! :track-external-link-clicked
                                                  {:path (auth/auth-url)
                                                   :event "Signup Clicked"
                                                   :properties {:source "username-overlay"}}))
                              :role "button"}
            "Sign Up"]]
          [:div.menu-footer
           [:a.menu-footer-link {:on-click #(cast! :overlay-closed)
                                 :role "button"}
            "No thanks."]]])))))

(def overlay-components
  {:info {:component info
          :type :prompt}
   :shortcuts {:title "Shortcuts"
               :component shortcuts
               :type :menu}
   :start {:title "Precursor"
           :component start
           :type :menu}
   :invite {:title "Invite Collaborators"
           :component invite
           :type :menu}
   :username {:component username
              :type :prompt}
   :doc-viewer {:title "Recent Documents"
                :component doc-viewer/doc-viewer
                :type :menu}})

(defn overlay [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            overlay-components (map #(get overlay-components %) (get-in app state/overlays-path))
            title (:title (last overlay-components))]
        (html
          [:div.app-overlay {:on-click #(cast! :overlay-closed)}
           [:div.app-overlay-background]
            [:aside.app-overlay-menu {:on-click #(.stopPropagation %)}
             [:div.menu-header
              [:a.menu-back {:on-click #(cast! :overlay-closed)
                             :role "button"}]
              (when title
               [:div.menu-title
                [:h4 title]])]
             [:div.menu-body
              (for [component overlay-components]
               (om/build (:component component) app))]]])))))
