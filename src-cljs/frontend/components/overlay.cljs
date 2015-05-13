(ns frontend.components.overlay
  (:require [cemerick.url :as url]
            [clojure.set :as set]
            [clojure.string :as str]
            [datascript :as d]
            [frontend.analytics :as analytics]
            [frontend.async :refer [put!]]
            [frontend.auth :as auth]
            [frontend.components.common :as common]
            [frontend.components.connection :as connection]
            [frontend.components.doc-viewer :as doc-viewer]
            [frontend.components.document-access :as document-access]
            [frontend.components.permissions :as permissions]
            [frontend.components.plan :as plan]
            [frontend.components.team :as team]
            [frontend.datascript :as ds]
            [frontend.models.doc :as doc-model]
            [frontend.state :as state]
            [frontend.urls :as urls]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.date :refer (date->bucket)]
            [goog.dom]
            [goog.dom.Range]
            [goog.dom.selection]
            [goog.labs.userAgent.browser :as ua]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [sablono.core :refer (html)])
  (:import [goog.ui IdGenerator]))

(defn share-input [{:keys [url placeholder]
                    :or {placeholder "Copy the url to share"}} owner]
  (reify
    om/IRender
    (render [_]
      (html
       [:form.menu-invite-form.make
        [:input {:type "text"
                 :required "true"
                 :data-adaptive ""
                 :onMouseDown (fn [e]
                                (set! (.-value (.-target e)) url) ; send cursor to end of input
                                (.focus (.-target e))
                                (goog.dom.selection/setStart (.-target e) 0)
                                (goog.dom.selection/setEnd (.-target e) 10000)
                                (utils/stop-event e))
                 :value url}]
        [:label {:data-placeholder placeholder}]]))))

(defn auth-link [app owner {:keys [source] :as opts}]
  (reify
    om/IDisplayName (display-name [_] "Overlay Auth Link")
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
            {:href (auth/auth-url :source source)
             :role "button"}
            (common/icon :login)
            [:span "Log in"]]))))))

(defn start [app owner]
  (reify
    om/IDisplayName (display-name [_] "Overlay Start")
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
            {:href "/new"
             :role "button"}
            (common/icon :plus)
            [:span "New"]]
           [:a.vein.make
            {:on-click         #(cast! :your-docs-opened)
             :on-touch-end #(do (cast! :your-docs-opened) (.preventDefault %))
             :role "button"}
            (common/icon :docs)
            [:span "Documents"]]
           ;; TODO: should this use the permissions model? Would have to send some
           ;;       info about the document
           (if (auth/has-document-access? app (:document/id app))
             [:a.vein.make
              {:on-click         #(cast! :sharing-menu-opened)
               :on-touch-end #(do (cast! :sharing-menu-opened) (.preventDefault %))
               :role "button"}
              (common/icon :sharing)
              [:span "Sharing"]]

             [:a.vein.make
              {:on-click         #(cast! :document-permissions-opened)
               :on-touch-end #(do (cast! :document-permissions-opened) (.preventDefault %))
               :role "button"}
              (common/icon :users)
              [:span "Request Access"]])
           [:a.vein.make
            {:on-click         #(cast! :export-menu-opened)
             :on-touch-end #(do (cast! :export-menu-opened) (.preventDefault %))
             :role "button"}
            (common/icon :download)
            [:span "Export"]]
           [:a.vein.make
            {:on-click         #(cast! :shortcuts-menu-opened)
             :on-touch-end #(do (cast! :shortcuts-menu-opened) (.preventDefault %))
             :class "mobile-hidden"
             :role "button"}
            (common/icon :command)
            [:span "Shortcuts"]]
           (om/build auth-link app {:opts {:source "start-overlay"}})])))))


(defn private-sharing [app owner]
  (reify
    om/IDisplayName (display-name [_] "Private Sharing")
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
          [:div.content
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
             (permissions/render-access-entity access-entity cast!))])))))

(defn read-only-sharing-for-admin [app owner]
  (reify
    om/IDisplayName (display-name [_] "Read-Only Sharing (admin)")
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
         [:div.content
          [:h2.make
           "This document is read-only."]

          [:p.make
           "Anyone with the url can see the doc and chat, but can't edit the canvas. "
           "Share the url to show off your work."]
          (om/build share-input {:url (urls/absolute-doc-url doc-id)})

          [:p.make
           "Add your teammate's email to grant them full access."]
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
            (permissions/render-access-entity access-entity cast!))])))))

(defn read-only-sharing-for-viewer [app owner]
  (reify
    om/IDisplayName (display-name [_] "Read-Only sharing (viewer)")
    om/IInitState (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (d/listen! (om/get-shared owner :db)
                 (om/get-state owner :listener-key)
                 (fn [tx-report]
                   ;; TODO: better way to check if state changed
                   (when (seq (filter #(or (= :access-request/document (:a %)))
                                      (:tx-data tx-report)))
                     (om/refresh! owner)))))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :db) (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [db cast!]} (om/get-shared owner)
            doc-id (:document/id app)
            document (d/entity @db doc-id)
            access-requests (ds/touch-all '[:find ?t :in $ ?doc-id :where [?t :access-request/document ?doc-id]] @db doc-id)]
        (html
         [:div.content
          [:h2.make
           "This document is read-only."]
          (if (:cust app)
            (list
             [:p.make
              "You can view this doc and chat, but anything you prototype here will only be visible to you."]
             (if (empty? access-requests)
               (list
                [:p.make
                 (if (:team app)
                   "Request full access below."
                   (list
                    "You can try to request full access or even "
                    [:a {:href "/new"} "create your own"]
                    " document."))]
                [:div.menu-buttons
                 [:a.make.menu-button {:on-click #(cast! :permission-requested {:doc-id doc-id})
                                       :role "button"}
                  "Request Access"]])
               [:p.make
                [:span
                 "Okay, we notified the owner of this document about your request. "
                 "While you wait for a response, try prototyping in "]
                [:a {:href "/new"} "your own document"]
                [:span "."]]))
            (list
             [:p.make
              "You can view this doc and chat, but anything you prototype here won't save. "
              "If you sign in with Google you can request full access from the owner of this document."]
             [:div.calls-to-action.make
              (om/build common/google-login {:source "Permission Denied Menu"})]))])))))

(defn read-only-sharing [app owner]
  (reify
    om/IDisplayName (display-name [_] "Read-Only Sharing")
    om/IRender
    (render [_]
      (if (auth/contains-scope? auth/scope-heirarchy (:max-document-scope app) :admin)
        (om/build read-only-sharing-for-admin app)
        (om/build read-only-sharing-for-viewer app)))))

(defn public-sharing [app owner]
  (reify
    om/IDisplayName (display-name [_] "Public Sharing")
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            invite-to (or (get-in app state/invite-to-path) "")]
        (html
          [:div.content
           [:h2.make
            "This document is public."]
           (if-not (:cust app)
             (list
               [:p.make
                "It's visible to anyone with the url.
                Sign in with your Google account to send an invite or give someone your url."]
               [:div.calls-to-action.make
                (om/build common/google-login {:source "Public Sharing Menu"})])

             (list
               [:p.make
                "Anyone with the url can view and edit."]

               (om/build share-input {:url (urls/absolute-doc-url (:document/id app))})

               [:p.make
                "Email or text a friend to invite them to collaborate:"]
               [:form.menu-invite-form.make
                {:on-submit #(do (cast! :invite-submitted)
                               false)
                 :on-key-down #(when (= "Enter" (.-key %))
                                 (cast! :invite-submitted)
                                 false)}
                [:input
                 {:type "text"
                  :required "true"
                  :data-adaptive ""
                  :value (or invite-to "")
                  :on-change #(cast! :invite-to-changed {:value (.. % -target -value)})}]
                [:label
                 {:data-placeholder (cond (pos? (.indexOf invite-to "@"))
                                          "Email address"
                                          (= 10 (count (str/replace invite-to #"[^0-9]" "")))
                                          "Phone number"
                                          :else "Email address or phone number")
                  :data-placeholder-nil "Email address or phone number?"
                  :data-placeholder-forgot "Don't forget to submit!"}]]))
           (when-let [response (first (get-in app (state/invite-responses-path (:document/id app))))]
             [:div response])
           ;; TODO: keep track of invites
           ])))))

(defn unknown-sharing [app owner]
  (reify
    om/IDisplayName (display-name [_] "Unknown Sharing")
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            invite-to (or (get-in app state/invite-to-path) "")])
      (html
       [:div.content
        [:h2.make "Share this document"]
        (if-not (:cust app)
          (list
           [:p.make
            "Sign in with your Google account to send an invite or give someone your url."]
           [:div.calls-to-action.make
            (om/build common/google-login {:source "Public Sharing Menu"})])

          (om/build share-input {:url (urls/absolute-doc-url (:document/id app))}))]))))

(defn sharing [app owner]
  (reify
    om/IDisplayName (display-name [_] "Overlay Sharing")
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
            doc-id (:document/id app)
            doc (doc-model/find-by-id @db doc-id)
            no-private-docs? (not (contains? (get-in app [:cust :flags]) :flags/private-docs))
            cant-edit-privacy? (when-not (:team app)
                                 (not (auth/owner? @db doc (get-in app [:cust]))))]
        (html
         [:div.menu-view
          (case (:document/privacy doc)
            :document.privacy/public (om/build public-sharing app)
            :document.privacy/private (om/build private-sharing app)
            ;;read-only sharing needs to know the user's permission for the doc
            :document.privacy/read-only (om/build read-only-sharing app)
            (om/build unknown-sharing app))

          [:form.privacy-select.vein.make.stick
           [:input.privacy-radio {:type "radio"
                                  :hidden "true"
                                  :id "privacy-public"
                                  :name "privacy"
                                  :checked (keyword-identical? :document.privacy/public (:document/privacy doc))
                                  :disabled (keyword-identical? :document.privacy/public (:document/privacy doc))
                                  :onChange #(if cant-edit-privacy?
                                               (utils/stop-event %)
                                               (cast! :document-privacy-changed
                                                      {:doc-id doc-id
                                                       :setting :document.privacy/public}))}]
           [:label.privacy-label {:class (when cant-edit-privacy? "disabled")
                                  :title (when cant-edit-privacy? "You must be the owner of this doc to change its privacy.")
                                  :for "privacy-public"
                                  :role "button"}
            (common/icon :public)
            [:span "Public"]
            (when cant-edit-privacy?
              [:small "(privacy change requires owner)"])]
           [:input.privacy-radio {:type "radio"
                                  :hidden "true"
                                  :id "privacy-read-only"
                                  :name "privacy"
                                  :checked (keyword-identical? :document.privacy/read-only (:document/privacy doc))
                                  :disabled cant-edit-privacy?
                                  :onChange #(if cant-edit-privacy?
                                               (utils/stop-event %)
                                               (cast! :document-privacy-changed
                                                      {:doc-id doc-id
                                                       :setting :document.privacy/read-only}))}]
           [:label.privacy-label {:class (when cant-edit-privacy? "disabled")
                                  :title (when cant-edit-privacy? "You must be the owner of this doc to change its privacy.")
                                  :for "privacy-read-only"
                                  :role "button"}
            (common/icon :read-only)
            [:span "Read-only"]
            (when cant-edit-privacy?
              [:small "(privacy change requires owner)"])]
           (if no-private-docs?
             [:a.vein.external {:href "/pricing"}
              (common/icon :private)
              [:span "Private"]
              [:small "(start a free trial)"]
              (common/icon :arrow-right)]
             (list
              [:input.privacy-radio {:type "radio"
                                     :hidden "true"
                                     :id "privacy-private"
                                     :name "privacy"
                                     :checked (keyword-identical? :document.privacy/private (:document/privacy doc))
                                     :disabled (boolean cant-edit-privacy?)
                                     :onChange #(if cant-edit-privacy?
                                                  (utils/stop-event %)
                                                  (cast! :document-privacy-changed
                                                         {:doc-id doc-id
                                                          :setting :document.privacy/private}))}]
              [:label.privacy-label {:class (when cant-edit-privacy? "disabled")
                                     :title (when cant-edit-privacy? "You must be the owner of this doc to change its privacy.")
                                     :for "privacy-private"
                                     :role "button"}
               (common/icon :private)
               [:span "Private"]
               (when cant-edit-privacy?
                 [:small "(privacy change requires owner)"])]))]])))))

(defn export [app owner]
  (reify
    om/IDisplayName (display-name [_] "Export Menu")
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            doc-id (:document/id app)]
        (html
         [:div.menu-view
          [:a.vein.make {:href (urls/absolute-doc-svg doc-id :query {:dl true})
                         :target "_self"}
           (common/icon :file-svg) "Download as SVG"]
          [:div.content.make (om/build share-input {:url (urls/absolute-doc-svg doc-id)
                                                    :placeholder "or use this url"})]

          [:a.vein.make {:href (urls/absolute-doc-pdf doc-id :query {:dl true})
                         :target "_self"}
           (common/icon :file-pdf) "Download as PDF"]
          [:div.content.make (om/build share-input {:url (urls/absolute-doc-pdf doc-id)
                                                    :placeholder "or use this url"})]

          [:a.vein.make {:href (urls/absolute-doc-png doc-id :query {:dl true})
                         :target "_self"}
           (common/icon :file-png) "Download as PNG"]
          [:div.content.make (om/build share-input {:url (urls/absolute-doc-png doc-id)
                                                    :placeholder "or use this url"})]])))))

(defn info [app owner]
  (reify
    om/IDisplayName (display-name [_] "Overlay Info")
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:div.menu-view
          [:div.content
           [:h2.make
            "What is Precursor?"]
           [:p.make
            "Precursor is a no-nonsense prototyping tool.
             Use it for wireframing, sketching, and brainstorming.
             Invite your team to collaborate instantly."
            ]
           (when-not (:cust app)
             (list
              [:p.make
               "Everyone's ideas made with Precursor save automatically.
                 And if you sign in with Google we'll even keep track of which ones are yours."]
              [:div.calls-to-action.make
               (om/build common/google-login {:source "Username Menu"})]))
           [:a.vein.make
            {:href "/home"
             :role "button"}
            [:span "Home"]]
           [:a.vein.make
            {:href "/pricing"
             :role "button"}
            [:span "Pricing"]]
           [:a.vein.make
            {:href "/blog"
             :target "_self"
             :role "button"}
            [:span "Blog"]]
           [:a.vein.make
            {:href "https://twitter.com/PrecursorApp"
             :on-click #(analytics/track "Twitter link clicked" {:location "info overlay"})
             :target "_blank"
             :title "@PrecursorApp"
             :role "button"}
            [:span "Twitter"]]
           [:a.vein.make
            {:href "mailto:hi@prcrsr.com?Subject=I%20have%20feedback"
             :target "_self"
             :role "button"}
            [:span "Email"]]
           [:a.vein.make
            {:on-click #(cast! :connection-info-opened)
             :on-touch-end #(do (cast! :connection-stats-opened) (.preventDefault %))
             :role "button"}
            [:span "Connection Info"]]]
          (common/mixpanel-badge)])))))

(defn shortcuts [app owner]
  (reify
    om/IDisplayName (display-name [_] "Overlay Shortcuts")
    om/IInitState (init-state [_] {:copy-paste-works? (ua/isChrome)})
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:div.menu-view
          [:div.content
           [:table.shortcuts-items
            [:tbody
             ;;
             ;; keys
             ;;
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
             ;;
             ;; edit
             ;;
             [:tr.make
              [:td {:col-span "2"}]]
             (when (om/get-state owner [:copy-paste-works?])
               (list
                (html
                 [:tr.make
                  [:td
                   [:div.shortcuts-keys
                    [:div.shortcuts-key {:title "Command Key"} (common/icon :command)]
                    [:div.shortcuts-key {:title "C Key"} "C"]]]
                  [:td [:div.shortcuts-result {:title "Hold command, press \"C\"."} "Copy"]]])
                (html
                 [:tr.make
                  [:td
                   [:div.shortcuts-keys
                    [:div.shortcuts-key {:title "Command Key"} (common/icon :command)]
                    [:div.shortcuts-key {:title "V Key"} "V"]]]
                  [:td [:div.shortcuts-result {:title "Hold command, press \"V\"."} "Paste"]]])))
             [:tr.make
              [:td
               [:div.shortcuts-keys
                [:div.shortcuts-key {:title "Command Key"} (common/icon :command)]
                [:div.shortcuts-key {:title "Z Key"} "Z"]]]
              [:td [:div.shortcuts-result {:title "Hold command, press \"Z\"."} "Undo"]]]
             ;;
             ;; scroll
             ;;
             [:tr.make
              [:td {:col-span "2"}]]
             [:tr.make
              [:td
               [:div.shortcuts-keys
                [:div.shortcuts-key  {:title "Option Key"} (common/icon :option)]
                [:div.shortcuts-misc {:title "Scroll Wheel"} (common/icon :scroll)]]]
              [:td [:div.shortcuts-result {:title "Hold option, scroll."} "Zoom"]]]
             [:tr.make
              [:td
               [:div.shortcuts-keys
                [:div.shortcuts-key {:title "Shift Key"} (common/icon :shift)]
                [:div.shortcuts-misc {:title "Scroll Wheel"} (common/icon :scroll)]]]
              [:td [:div.shortcuts-result {:title "Hold shift, scroll."} "Pan"]]]
             ;;
             ;; click
             ;;
             [:tr.make
              [:td {:col-span "2"}]]
             [:tr.make
              [:td
               [:div.shortcuts-keys
                [:div.shortcuts-key {:title "Space Key"} (common/icon :space)]
                [:div.shortcuts-misc {:title "Left Click"} (common/icon :click)]]]
              [:td [:div.shortcuts-result {:title "Hold space, click and drag to pan."} "Pan"]]]
             [:tr.make
              [:td
               [:div.shortcuts-keys
                [:div.shortcuts-key {:title "Shift Key"} (common/icon :shift)]
                [:div.shortcuts-misc {:title "Left Click"} (common/icon :click)]]]
              [:td [:div.shortcuts-result {:title "Hold shift, click multiple shapes."} "Multi-select"]]]
             [:tr.make
              [:td
               [:div.shortcuts-keys
                [:div.shortcuts-key  {:title "Option Key"} (common/icon :option)]
                [:div.shortcuts-misc {:title "Left Click + Drag"} (common/icon :click)]]]
              [:td [:div.shortcuts-result {:title "Hold option, drag shape(s)."} "Duplicate"]]]
             [:tr.make
              [:td
               [:div.shortcuts-keys
                [:div.shortcuts-key {:title "Control Key"} (common/icon :control)]
                [:div.shortcuts-key {:title "Shift Key"} (common/icon :shift)]
                [:div.shortcuts-misc {:title "Left Click + Drag"} (common/icon :click)]]]
              [:td [:div.shortcuts-result {:title "Hold control & shift, drag shape."} "Connect"]]]
             ]]]])))))

(defn username [app owner]
  (reify
    om/IDisplayName (display-name [_] "Overlay Username")
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:div.menu-view
          [:div.content
           [:h2.make
            "Let's change that name."]
           [:p.make
            "Chatting with teammates is easier when you can identify each other.
            Sign in with Google and you'll be able to change your name that gets displayed in chat."]
           [:div.calls-to-action.make
            (om/build common/google-login {:source "Username Menu"})]]])))))

(def overlay-components
  {:info {:title "About"
          :component info}
   :shortcuts {:title "Shortcuts"
               :component shortcuts}
   :start {:title "Precursor"
           :component start}
   :sharing {:title "Sharing"
             :component sharing}
   :export {:title "Export Document"
             :component export}
   :username {:component username}
   :doc-viewer {:title "Recent Documents"
                :component doc-viewer/doc-viewer}
   :document-permissions {:title "Request Access"
                          :component document-access/permission-denied-overlay}
   :connection-info {:title "Connection Info"
                     :component connection/connection-info}
   :roster {:title "Team"
            :component team/team-start}
   :team-settings {:title "Add Teammates"
                   :component team/team-settings}
   :team-doc-viewer {:title "Team Documents"
                     :component doc-viewer/team-doc-viewer}
   :your-teams {:title "Your Teams"
                :component team/your-teams}
   :request-team-access {:title "Request Access"
                         :component team/request-access}
   :plan {:title "Billing"
          :component plan/plan-menu}})

(defn namespaced? [kw]
  (namespace kw))

(defn overlay-component-key [overlay-key]
  (if (namespaced? overlay-key)
    (keyword (namespace overlay-key))
    overlay-key))

(defn get-component [overlay-key]
  (get overlay-components (overlay-component-key overlay-key)))

(defn overlay [app owner]
  (reify
    om/IDisplayName (display-name [_] "Overlay")
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:div.menu
          [:div.menu-header
           (for [overlay-key (get-in app state/overlays-path)
                 :let [component (get-component overlay-key)]]
             (html
              [:h4.menu-heading {:title (:title component) :react-key (:title component)}
               (if (namespaced? overlay-key)
                 (str (:title component) " " (str/capitalize (name overlay-key)))
                 (:title component))]))]
          [:div.menu-body
           (for [overlay-key (get-in app state/overlays-path)
                 :let [component (get-component overlay-key)]]
             (om/build (:component component) app {:react-key overlay-key
                                                   :opts {:submenu (when (namespaced? overlay-key)
                                                                     (keyword (name overlay-key)))}}))]])))))
