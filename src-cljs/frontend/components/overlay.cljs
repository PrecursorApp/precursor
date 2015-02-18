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
            [frontend.components.document-access :as document-access]
            [frontend.datascript :as ds]
            [frontend.models.doc :as doc-model]
            [frontend.scroll :as scroll]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.date :refer (date->bucket)]
            [goog.dom]
            [goog.labs.userAgent.browser :as ua]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [sablono.core :refer (html)])
  (:import [goog.ui IdGenerator]))

(defn auth-link [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            login-button-learned? (get-in app state/login-button-learned-path)]
        (html
         (if (:cust app)
           [:form.vein.make.stick
            {:method "post"
             :action "/logout"
             :ref "logout-form"
             :role "button"}
            [:input
             {:type "hidden"
              :name "__anti-forgery-token"
              :value (utils/csrf-token)}]
            [:input
             {:type "hidden"
              :name "redirect-to"
              :value (-> (.-location js/window)
                         (.-href)
                         (url/url)
                         :path)}]
            [:a.vein.make
             {:on-click         #(.submit (om/get-node owner "logout-form"))
              :on-touch-end #(do (.submit (om/get-node owner "logout-form")) (.preventDefault %))
              :role "button"}
             (common/icon :logout)
             [:span "Log out"]]]

           [:a.vein.make.stick
            {:href (auth/auth-url)
             :role "button"
             :on-click #(do
                          (.preventDefault %)
                          (cast! :login-button-clicked)
                          (cast! :track-external-link-clicked {:path (auth/auth-url)
                                                               :event "Signup Clicked"}))
             :on-touch-end #(do
                              (.preventDefault %)
                              (cast! :login-button-clicked)
                              (cast! :track-external-link-clicked {:path (auth/auth-url)
                                                                   :event "Signup Clicked"}))}
            (common/icon :login)
            [:span "Log in"]]))))))

(defn start [app owner]
  (reify
    om/IInitState (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (d/listen! (om/get-shared owner :db)
                 (om/get-state owner :listener-key)
                 (fn [tx-report]
                   ;; TODO: better way to check if state changed
                   (when-let [chat-datoms (seq (filter #(= :document/privacy (:a %))
                                                       (:tx-data tx-report)))]
                     (om/refresh! owner)))))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :db) (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)
            doc (doc-model/find-by-id @db (:document/id app))]
        (html
         [:div.menu-view
          [:a.vein.make
           {:on-click         #(cast! :overlay-info-toggled)
            :on-touch-end #(do (cast! :overlay-info-toggled) (.preventDefault %))
            :role "button"}
           (common/icon :info)
           [:span "About"]]
          [:a.vein.make
           {:on-click         #(cast! :newdoc-button-clicked)
            :on-touch-end #(do (cast! :newdoc-button-clicked) (.preventDefault %))
            :href "/"
            :target "_self"
            :role "button"}
           (common/icon :newdoc)
           [:span "New Document"]]
          [:a.vein.make
           {:on-click         #(cast! :your-docs-opened)
            :on-touch-end #(do (cast! :your-docs-opened) (.preventDefault %))
            :role "button"}
           (common/icon :clock)
           [:span "Your Documents"]]
          ;; TODO: should this use the permissions model? Would have to send some
          ;;       info about the document
          (if (auth/has-document-access? app (:document/id app))
            [:a.vein.make
             {:on-click         #(cast! :sharing-menu-opened)
              :on-touch-end #(do (cast! :sharing-menu-opened) (.preventDefault %))
              :role "button"}
             (common/icon :share)
             [:span "Sharing"]]

            [:a.vein.make
             {:on-click         #(cast! :document-permissions-opened)
              :on-touch-end #(do (cast! :document-permissions-opened) (.preventDefault %))
              :role "button"}
             (common/icon :users)
             [:span "Request Access"]])
          [:a.vein.make
           {:on-click         #(cast! :shortcuts-menu-opened)
            :on-touch-end #(do (cast! :shortcuts-menu-opened) (.preventDefault %))
            :class "mobile-hidden"
            :role "button"}
           (common/icon :command)
           [:span "Shortcuts"]]
          [:a.vein.make
           {:href "/blog"
            :target "_self"
            :role "button"}
           (common/icon :blog)
           [:span "Blog"]]

          (om/build auth-link app)])))))

(defn format-access-date [date]
  (date->bucket date :sentence? true))

;; TODO: add types to db
(defn access-entity-type [access-entity]
  (cond (contains? access-entity :permission/document)
        :permission

        (contains? access-entity :access-grant/document)
        :access-grant

        (contains? access-entity :access-request/document)
        :access-request

        :else nil))

;; TODO: this should call om/build somehow
(defmulti render-access-entity (fn [entity cast!]
                                 (access-entity-type entity)))

(defmethod render-access-entity :permission
  [entity cast!]
  [:div.access-card.make
   [:div.access-avatar
    [:img
     {:src (utils/gravatar-url (:permission/cust entity))}]]
   [:div.access-details
    [:span
     {:title (:permission/cust entity)} (:permission/cust entity)]
    [:span.access-status
     (str "Was granted access " (format-access-date (:permission/grant-date entity)))]]])

(defmethod render-access-entity :access-grant
  [entity cast!]
  [:div.access-card.make
   [:div.access-avatar
    [:img
     {:src (utils/gravatar-url (:access-grant/email entity))}]]
   [:div.access-details
    [:span
     {:title (:access-grant/email entity)} (:access-grant/email entity)]
    [:span.access-status
     (str "Was granted access " (format-access-date (:access-grant/grant-date entity)))]]])

(defmethod render-access-entity :access-request
  [entity cast!]
  [:div.access-card.make
   {:class (if (= :access-request.status/denied (:access-request/status entity))
             "denied"
             "requesting")}
   [:div.access-avatar
    [:img {:src (utils/gravatar-url (:access-request/cust entity))}]]
   [:div.access-details
    [:span {:title (:access-request/cust entity)} (:access-request/cust entity)]
    [:span.access-status
     (if (= :access-request.status/denied (:access-request/status entity))
       (str "Was denied access " (format-access-date (:access-request/deny-date entity)))
       (str "Requested access " (format-access-date (:access-request/create-date entity))))]]
   [:div.access-options
    (when-not (= :access-request.status/denied (:access-request/status entity))
      [:button.access-option
       {:role "button"
        :class "negative"
        :title "Decline"
        :on-click #(cast! :access-request-denied {:request-id (:db/id entity)
                                                  :doc-id (:access-request/document entity)})}
       (common/icon :times)])
    [:button.access-option
     {:role "button"
      :class "positive"
      :title "Approve"
      :on-click #(cast! :access-request-granted {:request-id (:db/id entity)
                                                 :doc-id (:access-request/document entity)})}
     (common/icon :check)]]])

(defn private-sharing [app owner]
  (reify
    om/IInitState (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (d/listen! (om/get-shared owner :db)
                 (om/get-state owner :listener-key)
                 (fn [tx-report]
                   ;; TODO: better way to check if state changed
                   (when (seq (filter #(or (= :document/privacy (:a %))
                                           (= :permission/document (:a %))
                                           (= :access-grant/document (:a %))
                                           (= :access-request/document (:a %))
                                           (= :access-request/status (:a %)))
                                      (:tx-data tx-report)))
                     (om/refresh! owner)))))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :db) (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)
            doc-id (:document/id app)
            doc (doc-model/find-by-id @db doc-id)
            permission-grant-email (get-in app state/permission-grant-email-path)
            permissions (ds/touch-all '[:find ?t :in $ ?doc-id :where [?t :permission/document ?doc-id]] @db doc-id)
            access-grants (ds/touch-all '[:find ?t :in $ ?doc-id :where [?t :access-grant/document ?doc-id]] @db doc-id)
            access-requests (ds/touch-all '[:find ?t :in $ ?doc-id :where [?t :access-request/document ?doc-id]] @db doc-id)]
        (html
          [:article
           [:h2.make
            "This document is private."]
           [:p.make
            "It's only visible to users with access."
            " Add your teammate's email to grant them access."]
           [:form.menu-invite-form.make
            {:on-submit #(do (cast! :permission-grant-submitted)
                           false)
             :on-key-down #(when (= "Enter" (.-key %))
                             (cast! :permission-grant-submitted)
                             false)}
            [:input
             {:type "text"
              :required "true"
              :data-adaptive ""
              :value (or permission-grant-email "")
              :on-change #(cast! :permission-grant-email-changed {:value (.. % -target -value)})}]
            [:label
             {:data-placeholder "Teammate's email"
              :data-placeholder-nil "What's your teammate's email?"
              :data-placeholder-forgot "Don't forget to submit!"}]]
           (for [access-entity (sort-by (comp - :db/id) (concat permissions access-grants access-requests))]
             (render-access-entity access-entity cast!))])))))

(defn public-sharing [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            invite-email (get-in app state/invite-email-path)]
        (html
          [:article
           [:h2.make
            "This document is public."]
           [:p.make
            "It's visible to anyone with the url.
            Email a friend to invite them to collaborate."]
           (if-not (:cust app)
             [:a.make
              {:href (auth/auth-url)
               :role "button"
               :on-click #(do
                            (.preventDefault %)
                            (cast! :track-external-link-clicked
                                   {:path (auth/auth-url)
                                    :event "Signup Clicked"
                                    :properties {:source "username-overlay"}}))}
              "Sign Up"]

             [:form.menu-invite-form.make
              {:on-submit #(do (cast! :invite-submitted)
                             false)
               :on-key-down #(when (= "Enter" (.-key %))
                               (cast! :email-invite-submitted)
                               false)}
              [:input
               {:type "text"
                :required "true"
                :data-adaptive ""
                :value (or invite-email "")
                :on-change #(cast! :invite-email-changed {:value (.. % -target -value)})}]
              [:label
               {:data-placeholder "Collaborator's email"
                :data-placeholder-nil "What's your collaborator's email?"
                :data-placeholder-forgot "Don't forget to submit!"}]])
           (when-let [response (first (get-in app (state/invite-responses-path (:document/id app))))]
             [:div response])
           ;; TODO: keep track of invites
           ])))))

(defn sharing [app owner]
  (reify
    om/IInitState (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (d/listen! (om/get-shared owner :db)
                 (om/get-state owner :listener-key)
                 (fn [tx-report]
                   ;; TODO: better way to check if state changed
                   (when (seq (filter #(or (= :document/privacy (:a %)))
                                      (:tx-data tx-report)))
                     (om/refresh! owner)))))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :db) (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)
            invite-email (get-in app state/invite-email-path)
            doc-id (:document/id app)
            doc (doc-model/find-by-id @db doc-id)
            private? (= :document.privacy/private (:document/privacy doc))]
        (html
         [:div.menu-view
          (if private?
            (om/build private-sharing app)
            (om/build public-sharing app))

          (when (and (contains? (get-in app [:cust :flags]) :flags/private-docs)
                     (auth/owner? @db doc (get-in app [:cust])))
            [:form.privacy-select.vein.make.stick
             [:input.privacy-radio
              {:type "radio"
               :hidden "true"
               :id "privacy-public"
               :name "privacy"
               :checked (not private?)
               :onChange #(cast! :document-privacy-changed
                                 {:doc-id doc-id
                                  :setting :document.privacy/public})}]
             [:label.privacy-label
              {:for "privacy-public"
               :role "button"}
              (common/icon :globe)
              [:span "Public"]]
             [:input.privacy-radio
              {:type "radio"
               :hidden "true"
               :id "privacy-private"
               :name "privacy"
               :checked private?
               :onChange #(cast! :document-privacy-changed
                                 {:doc-id doc-id
                                  :setting :document.privacy/private})}]
             [:label.privacy-label
              {:for "privacy-private"
               :role "button"}
              (common/icon :lock)
              [:span "Private"]]])])))))

(defn info [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
          [:div.menu-view
           [:article
            [:h2.make
             "What is Precursor?"]
            [:p.make
             "Precursor is a no-nonsense prototyping tool.
             Use it for wireframing, sketching, and brainstorming.
             Invite your team to collaborate instantly.
             Have feedback or a great idea?
             Say "
             [:a
              {:href "mailto:hi@prcrsr.com?Subject=I%20have%20feedback"
               :title "We love feedback, good or bad."}
              "hi@prcrsr.com"]
             " or on "
             [:a
              {:href "https://twitter.com/PrecursorApp"
               :on-click #(analytics/track "Twitter link clicked" {:location "info overlay"})
               :title "@PrecursorApp"
               :target "_blank"}
              "Twitter"]
             "."]
            (if (:cust app)
              [:a.make
               {:on-click         #(cast! :overlay-menu-closed)
                :on-touch-end #(do (cast! :overlay-menu-closed) (.preventDefault %))
                :role "button"} "Okay"]
              (list
                [:p.make
                 "Sign up and we'll even keep track of all your docs.
                 Never lose a great idea again!"]
                [:a.make
                 {:href (auth/auth-url)
                  :role "button"
                  :on-click #(do
                               (.preventDefault %)
                               (cast! :track-external-link-clicked
                                      {:path (auth/auth-url)
                                       :event "Signup Clicked"
                                       :properties {:source "username-overlay"}}))
                  :on-touch-end #(do
                                   (.preventDefault %)
                                   (cast! :track-external-link-clicked
                                          {:path (auth/auth-url)
                                           :event "Signup Clicked"
                                           :properties {:source "username-overlay"}}))}
                 "Sign Up"]))]
           (common/mixpanel-badge)])))))

(defn shortcuts [app owner]
  (reify
    om/IInitState (init-state [_] {:copy-paste-works? (ua/isChrome)})
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:div.menu-view
          [:article
           [:table.shortcuts-items
            [:tbody
             ;;
             ;; keystrokes beginning with "option"
             ;;
             [:tr.make
              [:td
               [:div.shortcuts-keys
                [:div.shortcuts-key  {:title "Option Key"} (common/icon :option)]
                [:div.shortcuts-misc {:title "Left Click"} (common/icon :mouse)]]]
              [:td [:div.shortcuts-result {:title "Hold option, drag shape(s)."} "Duplicate"]]]
             [:tr.make
              [:td
               [:div.shortcuts-keys
                [:div.shortcuts-key  {:title "Option Key"} (common/icon :option)]
                [:div.shortcuts-misc {:title "Scroll Wheel"} (common/icon :scroll)]]]
              [:td [:div.shortcuts-result {:title "Hold option, scroll."} "Zoom"]]]
             ;;
             ;; keystrokes beginning with "shift"
             ;;
             [:tr.make
              [:td {:col-span "2"}]]
             [:tr.make
              [:td
               [:div.shortcuts-keys
                [:div.shortcuts-key {:title "Shift Key"} (common/icon :shift)]
                [:div.shortcuts-misc {:title "Left Click"} (common/icon :mouse)]]]
              [:td [:div.shortcuts-result {:title "Hold shift, click multiple shapes."} "Stack"]]]
             [:tr.make
              [:td
               [:div.shortcuts-keys
                [:div.shortcuts-key {:title "Shift Key"} (common/icon :shift)]
                [:div.shortcuts-misc {:title "Scroll Wheel"} (common/icon :scroll)]]]
              [:td [:div.shortcuts-result {:title "Hold shift, scroll."} "Pan"]]]
             ;;
             ;; keystrokes beginning with "command"
             ;;
             [:tr.make
              [:td {:col-span "2"}]]
             (when (om/get-state owner [:copy-paste-works?])
               (list
                [:tr.make
                 [:td
                  [:div.shortcuts-keys
                   [:div.shortcuts-key {:title "Command Key"} (common/icon :command)]
                   [:div.shortcuts-key {:title "C Key"} "C"]]]
                 [:td [:div.shortcuts-result {:title "Hold command, press \"C\"."} "Copy"]]]
                [:tr.make
                 [:td
                  [:div.shortcuts-keys
                   [:div.shortcuts-key {:title "Command Key"} (common/icon :command)]
                   [:div.shortcuts-key {:title "V Key"} "V"]]]
                 [:td [:div.shortcuts-result {:title "Hold command, press \"V\"."} "Paste"]]]))
             [:tr.make
              [:td
               [:div.shortcuts-keys
                [:div.shortcuts-key {:title "Command Key"} (common/icon :command)]
                [:div.shortcuts-key {:title "Z Key"} "Z"]]]
              [:td [:div.shortcuts-result {:title "Hold command, press \"Z\"."} "Undo"]]]
             ;;
             ;; single keystrokes
             ;;
             [:tr.make
              [:td {:col-span "2"}]]
             [:tr.make
              [:td [:div.shortcuts-key {:title "V Key"} "V"]]
              [:td [:div.shortcuts-result {:title "Switch to Select Tool."} "Select"]]]
             [:tr.make
              [:td [:div.shortcuts-key {:title "M Key"} "M"]]
              [:td [:div.shortcuts-result {:title "Switch to Rectangle Tool."} "Rectangle"]]]
             [:tr.make
              [:td [:div.shortcuts-key {:title "L Key"} "L"]]
              [:td [:div.shortcuts-result {:title "Switch to Circle Tool."} "Circle"]]]
             [:tr.make
              [:td [:div.shortcuts-key {:title "Backslash Key"} "\\"]]
              [:td [:div.shortcuts-result {:title "Switch to Line Tool."} "Line"]]]
             [:tr.make
              [:td [:div.shortcuts-key {:title "N Key"} "N"]]
              [:td [:div.shortcuts-result {:title "Switch to Pen Tool."} "Pen"]]]
             [:tr.make
              [:td [:div.shortcuts-key {:title "T Key"} "T"]]
              [:td [:div.shortcuts-result {:title "Switch to Text Tool."} "Text"]]]
             [:tr.make
              [:td [:div.shortcuts-key {:title "1 Key"} "1"]]
              [:td [:div.shortcuts-result {:title "Initial view when entering doc."} "Origin"]]]
             [:tr.make
              [:td [:div.shortcuts-key {:title "2 Key"} "2"]]
              [:td [:div.shortcuts-result {:title "Return to previous view after jumping to origin."} "Return"]]]
             [:tr.make
              [:td [:div.shortcuts-key {:title "? Key"} "?"]]
              [:td [:div.shortcuts-result {:title "Hold shift, press \"/\"."} "Shortcuts"]]]
             [:tr.make
              [:td [:div.shortcuts-key {:title "Delete Key"} (common/icon :delete)]]
              [:td [:div.shortcuts-result {:title "Delete selected shape(s)."} "Delete"]]]
             [:tr.make
              [:td [:div.shortcuts-key {:title "Escape Key"} (common/icon :esc)]]
              [:td [:div.shortcuts-result {:title "Cancel action or close menu."} "Cancel"]]]
             ]]]])))))

(defn username [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:div.menu-view
          [:article
           [:h2.make
            "Let's change that name."]
           [:p.make
            "Sign up to change how your name appears in chat.
            Let your team know who you are while you collaborate together."]
           [:a.make
            {:href (auth/auth-url)
             :role "button"
             :on-click #(do
                          (.preventDefault %)
                          (cast! :track-external-link-clicked
                                 {:path (auth/auth-url)
                                  :event "Signup Clicked"
                                  :properties {:source "username-overlay"}}))}
            "Sign Up"]]])))))

(def overlay-components
  {:info {:title "About"
          :component info}
   :shortcuts {:title "Shortcuts"
               :component shortcuts}
   :start {:title "Precursor"
           :component start}
   :sharing {:title "Sharing"
             :component sharing}
   :username {:component username}
   :doc-viewer {:title "Recent Documents"
                :component doc-viewer/doc-viewer}
   :document-permissions {:title "Request Access"
                          :component document-access/permission-denied-overlay}})

(defn overlay [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            overlay-components (map #(get overlay-components %) (get-in app state/overlays-path))
            title (:title (last overlay-components))]
        (html
         [:div.menu
          [:div.menu-header
           (for [component overlay-components]
            [:h4
             {:title title}
             (:title component)])]
          [:div.menu-body
           (for [component overlay-components]
            (om/build (:component component) app))]])))))
