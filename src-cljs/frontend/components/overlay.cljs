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
            [frontend.scroll :as scroll]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [goog.dom]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]])
  (:import [goog.ui IdGenerator]))

(defn main-menu-button [data owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            main-menu-learned? (get-in data state/main-menu-learned-path)]
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
                                :data-right (when-not main-menu-learned?
                                              (if (overlay-visible? data) "Close Menu" "Open Menu"))
                                :title (when main-menu-learned?
                                         (if (overlay-visible? data) "Close Menu" "Open Menu"))}
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
          [:div.menu-view
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
            ;; TODO finish wiring up invite stuff -dk (12/09/14)
            [:a.menu-item {:on-click #(cast! :invite-menu-opened)
                           :role "button"}
             (common/icon :users)
             [:span "Invite Collaborators"]]
            [:a.menu-item {:on-click #(cast! :shortcuts-menu-opened)
                           :class "mobile-hidden"
                           :role "button"}
             (common/icon :command)
             [:span "Shortcuts"]]
            (om/build auth-link app)]])))))

(defn invite [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            invite-email (get-in app state/invite-email-path)]
        (html
         [:div.menu-view
          [:div.menu-view-frame
           [:article
            [:h2 "Share this with your team."]
            [:p "Send your teammates invites to come collaborate with you in this doc."]
            (if-not (:cust app)
              [:a.menu-button {:href (auth/auth-url)
                               :on-click #(do
                                            (.preventDefault %)
                                            (cast! :track-external-link-clicked
                                                   {:path (auth/auth-url)
                                                    :event "Signup Clicked"
                                                    :properties {:source "username-overlay"}}))
                               :role "button"}
               "Sign Up"]

              [:form.menu-invite-form {:on-submit #(do (cast! :invite-submitted)
                                                       false)
                                       :on-key-down #(when (= "Enter" (.-key %))
                                                       (cast! :email-invite-submitted)
                                                       false)}
               [:input {:type "text"
                        :required "true"
                        :data-adaptive ""
                        :value (or invite-email "")
                        :on-change #(cast! :invite-email-changed {:value (.. % -target -value)})}]
               [:label {:data-placeholder "Teammate's Email"
                        :data-placeholder-nil "What's your teammate's email?"
                        :data-placeholder-forgot "Don't forget to submit."}]])
            (when-let [response (first (get-in app (state/invite-responses-path (:document/id app))))]
              [:div response])
            ;; TODO: keep track of invites
            ;; [:p "You've sent 3 invitations to this doc."]
            ;; [:div.invite-recipient
            ;;  [:div.invite-recipient-email "fake@email.com"]
            ;;  [:a {:role "button"} "Resend"]]
            ;; [:div.invite-recipient
            ;;  [:div.invite-recipient-email "fake@email.com"]
            ;;  [:a {:role "button"} "Resend"]]
            ;; [:div.invite-recipient
            ;;  [:div.invite-recipient-email "fake@email.com"]
            ;;  [:a {:role "button"} "Resend"]]
            ]]])))))

(defn info [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
          [:div.menu-view
           [:div.menu-view-frame
            [:article
             [:h2 "What is Precursor?"]
             [:p "Precursor is a no-nonsense prototyping tool.
                 Use it for wireframing, sketching, and brainstorming. "
                 ;; TODO finish wiring up invite stuff -dk (12/09/14)
                 ;; [:a {:on-click #(cast! :invite-menu-opened)
                 ;;      :role "button"}
                 ;;  "Invite"]
                 [:a {:on-click #(cast! :invite-link-clicked)
                      :role "button"
                      :title "In chat, type \"/invite their@email.com\""}
                  "Invite"]
                 " your team to collaborate instantly.
                 Have feedback or a great idea?
                 Say "
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
                     Never lose a great idea again!"]
                 [:a.menu-button {:href (auth/auth-url)
                                  :on-click #(do
                                               (.preventDefault %)
                                               (cast! :track-external-link-clicked
                                                      {:path (auth/auth-url)
                                                       :event "Signup Clicked"
                                                       :properties {:source "username-overlay"}}))
                                  :role "button"}
                  "Sign Up"]))]
            [:footer {:class "mobile-hidden"}
             (common/mixpanel-badge)]]])))))

(defn shortcuts [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
          [:div.menu-view
           [:div.menu-view-frame
            [:article
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
             [:div.shortcuts-result "Undo"]]]]])))))

(defn username [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:div.menu-view
          [:div.menu-view-frame
           [:article
            [:h2 "Let's change that name."]
            [:p "Sign up to change how your name appears in chat.
                Let your team know who you are while you collaborate together."]
            [:a.menu-button {:href (auth/auth-url)
                             :on-click #(do
                                          (.preventDefault %)
                                          (cast! :track-external-link-clicked
                                                 {:path (auth/auth-url)
                                                  :event "Signup Clicked"
                                                  :properties {:source "username-overlay"}}))
                             :role "button"}
             "Sign Up"]]]])))))

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

(def screen
  [:svg {:view-box "0 0 1024 640"}
   [:path.layers {:d "M704,576H64V88h640V576z M248,152h352 M256,184h400 M664,216H248 M240,248h408 M96,280h544 M96,312h528 M96,344h560 M96,376h512 M672,408H96v168h576V408z M96,408 l336,168 M336,576l336-168"}]
   [:g.in-progress [:circle {:stroke-dasharray "8.0417,8.0417", :cx "160", :cy "184", :r "64"}]]
   [:g.in-progress
    [:line {:x1 "205.3", :y1 "138.7", :x2 "202.4", :y2 "141.6"}]
    [:line {:stroke-dasharray "8,8", :x1 "196.8", :y1 "147.2", :x2 "120.4", :y2 "223.6"}]
    [:line {:x1 "117.6", :y1 "226.4", :x2 "114.7", :y2 "229.3"}]]
   [:g.in-progress
    [:line {:x1 "205.3", :y1 "229.3", :x2 "202.4", :y2 "226.4"}]
    [:line {:stroke-dasharray "8,8", :x1 "196.8", :y1 "220.8", :x2 "120.4", :y2 "144.4"}]
    [:line {:x1 "117.6", :y1 "141.6", :x2 "114.7", :y2 "138.7"}]]
   [:path.chat {:d "M768,24v616 M768,576h256 M784,592v32"}]
   [:path.cursor {:d "M183.3,256.1v-13.4l9.5,9.5h-3.8 l2.2,5.3l-2.9,1.2l-2.2-5.3L183.3,256.1z"}]
   [:path.menu {:d "M1016,0H8C3.6,0,0,3.6,0,8v16h1024V8 C1024,3.6,1020.4,0,1016,0z"}]
   [:path.border {:d "M0,24v608c0,4.4,3.6,8,8,8h1008 c4.4,0,8-3.6,8-8V24"}]
   [:path.actions {:d "M16,12c0,2.2-1.8,4-4,4s-4-1.8-4-4s1.8-4,4-4S16,9.8,16,12z M44,8c-2.2,0-4,1.8-4,4s1.8,4,4,4 c2.2,0,4-1.8,4-4S46.2,8,44,8z M28,8c-2.2,0-4,1.8-4,4s1.8,4,4,4s4-1.8,4-4S30.2,8,28,8z"}]])

(defn past-center? [owner ref]
  (let [node (om/get-node owner ref)
        vh (.-height (goog.dom/getViewportSize))]
    (< (.-top (.getBoundingClientRect node)) (/ vh 2))))

;; TODO: update to new om so that we don't need this
(defn maybe-set-state! [owner korks value]
  (when (not= (om/get-state owner korks) value)
    (om/set-state! owner korks value)))

(defn overlay [app owner]
  (reify
    om/IInitState (init-state [_] {:past-center-featurettes #{}})
    om/IRenderState
    (render-state [_ {:keys [past-center-featurettes]}]
      (let [cast! (om/get-shared owner :cast!)
            overlay-components (map #(get overlay-components %) (get-in app state/overlays-path))
            title (:title (last overlay-components))]
        (html
         [:div.app-overlay {:on-click #(cast! :overlay-closed)
                            :on-scroll #(maybe-set-state! owner [:past-center-featurettes]
                                                          (set (filter (partial past-center? owner) ["1" "2" "3" "4" "5"])))}
           [:div.app-overlay-background]
            [:div.app-overlay-home
             [:div.jumbotron
              [:div.home-nav
               [:div.content
                [:a.nav-item {:role "button"} "Precursor"]
                [:a.nav-item {:role "button"} "Pricing"]
                [:a.nav-item {:role "button"} "Blog"]
                [:a.nav-item.google-login {:role "button"}
                 [:span.google-login-icon
                  (common/icon :google)]
                 [:span.google-login-body "Sign in"]]]]
              [:div.content
               [:h1 "Collaborate on every idea with your entire team."]
               [:h4 "Productive prototyping without all the nonsense."]
               [:button "Launch Precursor"]]]
             [:div.home-body
              [:article.featurette {:ref "1"
                                    :class (when (contains? past-center-featurettes "1") "active")}
               [:div.featurette-story
                [:h2 "Sharing prototypes should be simple."]
                [:p "Lorem ipsum dolor sit amet, consectetur adipiscing elit.
                    Sed felis enim, rhoncus a lobortis at, porttitor nec tellus.
                    Aliquam gravida consequat velit, ultrices porttitor turpis sagittis et."]]
               [:div.featurette-media screen]]
              [:article.featurette.featurette-how  {:ref "2"
                                                    :class (when (contains? past-center-featurettes "2") "active")}
               [:div.featurette-story
                [:h2 "Express your ideas efficiently with simple tools."]
                [:p "Lorem ipsum dolor sit amet, consectetur adipiscing elit.
                    Sed felis enim, rhoncus a lobortis at, porttitor nec tellus.
                    Aliquam gravida consequat velit, ultrices porttitor turpis sagittis et."]]
               [:div.featurette-media screen]]
              [:article.featurette.featurette-how {:ref "3"
                                                   :class (when (contains? past-center-featurettes "3") "active")}
               [:div.featurette-story
                [:h2 "Interact with your ideas before building them."]
                [:p "Lorem ipsum dolor sit amet, consectetur adipiscing elit.
                    Sed felis enim, rhoncus a lobortis at, porttitor nec tellus.
                    Aliquam gravida consequat velit, ultrices porttitor turpis sagittis et."]]
               [:div.featurette-media screen]]
              [:article.featurette.featurette-how {:ref "4"
                                                   :class (when (contains? past-center-featurettes "4") "active")}
               [:div.featurette-story
                [:h2 "Share your ideas faster without forgetting them."]
                [:p "Lorem ipsum dolor sit amet, consectetur adipiscing elit.
                    Sed felis enim, rhoncus a lobortis at, porttitor nec tellus.
                    Aliquam gravida consequat velit, ultrices porttitor turpis sagittis et."]]
               [:div.featurette-media screen]]
              [:article.featurette {:ref "5"
                                    :class (when (contains? past-center-featurettes "5") "active")}
               [:div.featurette-story
                [:h2 "Pure prototyping, just focus on the idea."]
                [:p "Lorem ipsum dolor sit amet, consectetur adipiscing elit.
                    Sed felis enim, rhoncus a lobortis at, porttitor nec tellus.
                    Aliquam gravida consequat velit, ultrices porttitor turpis sagittis et."]]
               [:div.featurette-media screen]]]]
            [:div.app-overlay-menu {:on-click #(.stopPropagation %)
                                    :style {:display "none"}}
             [:div.menu-header
              (for [component overlay-components]
               [:h4 {:title title} (:title component)])]
             [:div.menu-body
              (for [component overlay-components]
               (om/build (:component component) app))]]])))))
