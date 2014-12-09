(ns frontend.components.overlay
  (:require [cemerick.url :as url]
            [clojure.set :as set]
            [clojure.string :as str]
            [datascript :as d]
            [frontend.analytics :as analytics]
            [frontend.async :refer [put!]]
            [frontend.auth :as auth]
            [frontend.components.common :as common]
            [frontend.components.doc-viewer :as doc-viewer]
            [frontend.datascript :as ds]
            [frontend.overlay :refer [current-overlay overlay-visible? overlay-count]]
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
                                         (concat
                                           ["bkg-light"]
                                           (if (< 1 (overlay-count data))
                                             ["back"]
                                             ["close"])))
                                :data-right (when-not menu-button-learned? "Open Menu")
                                :title (when menu-button-learned? "Open Menu")}
           (common/icon :menu)])))))

(defn auth-link [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            login-button-learned? (get-in app state/login-button-learned-path)]
        (html
         (if (:cust app)
           [:form.menu-item {:method "post" :action "/logout" :ref "logout-form" :role "button"}
            [:input {:type "hidden" :name "__anti-forgery-token" :value (utils/csrf-token)}]
            [:input {:type "hidden" :name "redirect-to" :value (-> (.-location js/window)
                                                                   (.-href)
                                                                   (url/url)
                                                                   :path)}]
            [:a.menu-item {:on-click #(.submit (om/get-node owner "logout-form"))
                           :role "button"}
             (common/icon :logout)
             [:span "Log out"]]]
           [:a.menu-item  {:href (auth/auth-url)
                           :role "button"
                           :on-click #(do
                                        (.preventDefault %)
                                        (cast! :login-button-clicked)
                                        (cast! :track-external-link-clicked {:path (auth/auth-url)
                                                                             :event "Signup Clicked"}))}
            (common/icon :login)
            [:span "Log in"]]))))))

(defn start [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
          [:div.menu-view {:class (str "menu-view-" "start")}
           [:div.menu-view-frame
            [:a.menu-item {:on-click #(cast! :overlay-info-toggled)
                           :role "button"}
             (common/icon :info)
             [:span "About"]]
            [:a.menu-item {:on-click #(cast! :newdoc-button-clicked)
                           :href "/"
                           :target "_self"
                           :role "button"}
             (common/icon :newdoc)
             [:span "New Document"]]
            [:a.menu-item {:on-click #(cast! :your-docs-opened)
                           :role "button"}
             (common/icon :clock)
             [:span "Your Documents"]]
            [:a.menu-item {:on-click #(cast! :invite-menu-opened)
                           :role "button"}
             (common/icon :users)
             [:span "Invite Collaborators"]]
            [:a.menu-item {:on-click #(cast! :shortcuts-menu-opened)
                           :role "button"}
             (common/icon :command)
             [:span "Shortcuts"]]
            (om/build auth-link app)]])))))

(defn invite [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
          [:div.menu-view {:class (str "menu-view-" "invite")}
           [:article.menu-view-frame
            [:h2 "Show this idea to your team."]
            [:p "Send your team invites to come collaborate with you in this doc. Separate emails with a space or a comma."]
            [:form.menu-invite-form
             [:input {:type "text"
                      :required "true"
                      :data-adaptive ""}]
             [:label {:data-placeholder "Teammate's Email"
                      :data-placeholder-nil "What's your teammate's email?"
                      :data-placeholder-forgot "Don't forget to submit."}]]
            [:p "You've sent 3 invitations to this doc."]
            [:div.invite-recipient
             [:div.invite-recipient-email "fake@email.com"]
             [:a {:role "button"} "Resend"]]
            [:div.invite-recipient
             [:div.invite-recipient-email "fake@email.com"]
             [:a {:role "button"} "Resend"]]
            [:div.invite-recipient
             [:div.invite-recipient-email "fake@email.com"]
             [:a {:role "button"} "Resend"]]
            ]])))))

(defn info [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
          [:div.menu-view {:class (str "menu-view-" "info")}
           [:article.menu-view-frame
            [:h2 "What is Precursor?"]
            [:p "No-nonsense prototyping—perfect for wireframing, sketching, and brainstorming. "
                [:a {:on-click #(cast! :invite-menu-opened)
                     :role "button"
                     :title "In chat, type \"/invite their@email.com\""}
                 "Invite"]
                " your team and collaborate with them instantly.
                We just got started, so if you have feedback say "
                [:a {:href "mailto:hi@prcrsr.com?Subject=I%20have%20feedback" :title "We love feedback, good or bad."}
                 "hi@prcrsr.com"]
                " or on "
                [:a {:href "https://twitter.com/prcrsr_app"
                     :on-click #(analytics/track "Twitter link clicked" {:location "info overlay"})
                     :title "@prcrsr_app"
                     :target "_blank"}
                 "Twitter"]
                "."]
            (if (:cust app)
              [:a.menu-button {:on-click #(cast! :overlay-menu-closed) :role "button"} "Okay"]
              (list
                [:p "Sign up and we'll even keep track of all your docs.
                    Never lose a great idea ever again!"]
                [:a.menu-button {:href (auth/auth-url)
                                 :on-click #(do
                                              (.preventDefault %)
                                              (cast! :track-external-link-clicked
                                                     {:path (auth/auth-url)
                                                      :event "Signup Clicked"
                                                      :properties {:source "username-overlay"}}))
                                 :role "button"}
                 "Sign Up"]))
            (common/mixpanel-badge)]])))))

(defn shortcuts [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
          [:div.menu-view {:class (str "menu-view-" "shortcuts")}
           [:article.menu-view-frame
            [:h2 "Move fast, make things."]
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

(defn username [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:div.menu-view {:class (str "menu-view-" "username")}
          [:article.menu-view-frame
           [:h2 "Let's change that name."]
           [:p "Sign up to change how your name appears in chat.
               Let your team know who you are while you collaborate together!"]
           [:a.menu-button {:href (auth/auth-url)
                            :on-click #(do
                                         (.preventDefault %)
                                         (cast! :track-external-link-clicked
                                                {:path (auth/auth-url)
                                                 :event "Signup Clicked"
                                                 :properties {:source "username-overlay"}}))
                            :role "button"}
            "Sign Up"]]])))))

(def overlay-components
  {:info {:component info}
   :shortcuts {:title "Shortcuts"
               :component shortcuts}
   :start {:title "Precursor"
           :component start}
   :invite {:title "Invite Collaborators"
           :component invite}
   :username {:component username}
   :doc-viewer {:title "Recent Documents"
                :component doc-viewer/doc-viewer}})

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
              (for [component overlay-components]
               [:h4 {:title title} (:title component)])]
             [:div.menu-body
              (for [component overlay-components]
               (om/build (:component component) app))]]])))))
