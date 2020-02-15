(ns frontend.components.hud
  (:require [cljs.core.async :as async :refer [put!]]
            [clojure.string :as str]
            [datascript.core :as d]
            [frontend.auth :as auth]
            [frontend.colors :as colors]
            [frontend.components.common :as common]
            [frontend.config :as config]
            [frontend.cursors :as cursors]
            [frontend.db :as fdb]
            [frontend.models.chat :as chat-model]
            [frontend.models.doc :as doc-model]
            [frontend.overlay :refer [current-overlay overlay-visible? overlay-count]]
            [frontend.state :as state]
            [frontend.urls :as urls]
            [frontend.utils :as utils :include-macros true]
            [goog.dom]
            [goog.dom.Range]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [sablono.core :refer (html)])
  (:import [goog.ui IdGenerator]))

(defn menu [app owner]
  (reify
    om/IDisplayName (display-name [_] "Hud Menu")
    om/IDidMount (did-mount [_] (fdb/watch-doc-name-changes owner))
    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)
            main-menu-learned? (get-in app state/main-menu-learned-path)
            menu-visibile? (frontend.overlay/menu-overlay-visible? app)
            doc (doc-model/find-by-id @db (:document/id app))]
        (html
         [:a.hud-menu.hud-item.hud-toggle.menu-needed (merge {:role "button"
                                                              :class (when menu-visibile?
                                                                       (if (< 1 (overlay-count app))
                                                                         "back"
                                                                         "close"))
                                                              :data-right (when-not main-menu-learned?
                                                                            (if menu-visibile? "Close Menu" "Open Menu"))
                                                              :title (when main-menu-learned?
                                                                       (if menu-visibile? "Close Menu" "Open Menu"))}
                                                             (if menu-visibile?
                                                               (let [f #(let [nav-ch (:nav (om/get-shared owner :comms))]
                                                                          (if (= 1 (overlay-count app))
                                                                            (cast! :overlay-menu-closed)
                                                                            (put! nav-ch [:back!]))
                                                                          (.preventDefault %))]
                                                                 {:on-click f
                                                                  :on-touch-end f})
                                                               {:href (urls/overlay-path doc "start")})
                                                             (when-not (seq doc)
                                                               {:on-mouse-enter #(cast! :navigate-to-landing-doc-hovered)}))
          (common/icon :menu)])))))

(defn roster [app owner]
  (reify
    om/IDisplayName (display-name [_] "Hud Menu")
    om/IDidMount (did-mount [_] (fdb/watch-doc-name-changes owner))
    om/IRender
    (render [_]
      (let [{:keys [db cast!]} (om/get-shared owner)
            main-menu-learned? (get-in app state/main-menu-learned-path)
            roster-visible? (frontend.overlay/roster-overlay-visible? app)
            doc (doc-model/find-by-id @db (:document/id app))]
        (html
         [:a.hud-roster.hud-item.hud-toggle.menu-needed (merge {:role "button"
                                                                :class (when roster-visible?
                                                                         (if (< 1 (overlay-count app))
                                                                           "back"
                                                                           "close"))
                                                                :data-right (when-not main-menu-learned?
                                                                              (if roster-visible? "Close Menu" "Open Menu"))
                                                                :title (when main-menu-learned?
                                                                         (if roster-visible? "Close Menu" "Open Menu"))}
                                                               (if roster-visible?
                                                                 (let [f #(let [nav-ch (:nav (om/get-shared owner :comms))]
                                                                            (if (= 1 (overlay-count app))
                                                                              (cast! :overlay-menu-closed)
                                                                              (put! nav-ch [:back!]))
                                                                            (.preventDefault %))]
                                                                   {:on-click f
                                                                    :on-touch-end f})
                                                                 {:href (urls/overlay-path doc (if (:team app)
                                                                                                    "roster"
                                                                                                    "your-teams"))})
                                                               (when-not (seq doc)
                                                                 {:on-mouse-enter #(cast! :navigate-to-landing-doc-hovered)}))
          (if roster-visible?
            (common/icon :menu)
            (common/icon :users))])))))

(defn mouse-stats [_ owner]
  (reify
    om/IDisplayName (display-name [_] "Mouse Stats")
    om/IRender
    (render [_]
      (let [mouse (cursors/observe-mouse owner)]
        (dom/a #js {:className "mouse-stats"
                    :onMouseDown #((om/get-shared owner :cast!) :mouse-stats-clicked)
                    :role "button"}
               (str "{:x " (Math/round (:rx mouse 0)) " :y " (Math/round (:ry mouse 0)) "}"))))))

(defn tray [app owner]
  (reify
    om/IDisplayName (display-name [_] "Hud Tray")
    om/IInitState (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (fdb/watch-doc-name-changes owner)
      (d/listen! (om/get-shared owner :db)
                 (om/get-state owner :listener-key)
                 (fn [tx-report]
                   (when (seq (filter #(= :document/privacy (:a %)) (:tx-data tx-report)))
                     (om/refresh! owner)))))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :db) (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [db cast!]} (om/get-shared owner)
            welcome-info-learned? (get-in app state/welcome-info-learned-path)
            chat-opened? (get-in app state/chat-opened-path)
            document (doc-model/find-by-id @db (:document/id app))
            rejected-tx-count (get-in app (state/doc-tx-rejected-count-path (:document/id app)))]
        (html
         [:div.hud-tray.hud-item.width-canvas {:key (str "hud-tray-" chat-opened?)}
          (if-not welcome-info-learned?
            [:a.info-button {:href (urls/overlay-path document "info")
                             :on-click #(cast! :welcome-info-clicked)}
             "What is Precursor?"]

            [:div.doc-stats
             (om/build mouse-stats {} {:react-key "mouse-stats"})
             [:a.privacy-stats {:href (urls/overlay-path document "sharing")
                                :key rejected-tx-count
                                :class (when (pos? rejected-tx-count)
                                         (if (zero? (mod rejected-tx-count 2))
                                           "rejected-txes-a"
                                           "rejected-txes-b"))}
              (case (:document/privacy document)
                :document.privacy/public (common/icon :public)
                :document.privacy/read-only (common/icon :read-only)
                :document.privacy/private (common/icon :private)
                nil)]])])))))

(defn chat [app owner]
  (reify
    om/IDisplayName (display-name [_] "Hud Chat")
    om/IInitState
    (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (d/listen! (om/get-shared owner :db)
                 (om/get-state owner :listener-key)
                 (fn [tx-report]
                   ;; TODO: better way to check if state changed
                   (when-let [chat-datoms (seq (filter #(or (= :chat/body (:a %))
                                                            (= :document/chat-bot (:a %))) (:tx-data tx-report)))]
                     (om/refresh! owner)))))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :db) (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)
            chat-opened? (get-in app state/chat-opened-path)
            chat-button-learned? (get-in app state/chat-button-learned-path)
            viewers-count (count (remove (comp :hide-in-list? last) (get-in app [:subscribers :info])))
            last-read-time (get-in app (state/last-read-chat-time-path (:document/id app)))
            dummy-chat? (seq (d/datoms @db :aevt :document/chat-bot))
            unread-chat-count (chat-model/compute-unread-chat-count @db last-read-time)
            unread-chat-count (if last-read-time
                                unread-chat-count
                                ;; add one for the dummy message
                                (+ (if dummy-chat? 1 0) unread-chat-count))]
        (html
          [:a.hud-chat.hud-item.hud-toggle {:on-click #(cast! :chat-toggled)
                                            :on-touch-end #(do
                                                             (.preventDefault %)
                                                             (if (get-in app state/viewers-opened-path)
                                                               (do
                                                                 (cast! :chat-toggled)
                                                                 (cast! :viewers-closed))
                                                               (cast! :chat-toggled)))
                                            :class (when-not chat-opened? "open")
                                            :role "button"
                                            :data-left (when-not chat-button-learned? (if chat-opened? "Close Chat" "Open Chat"))
                                            :title     (when     chat-button-learned? (if chat-opened? "Close Chat" "Open Chat"))}
           (common/icon :chat-morph)
           (when (and (not chat-opened?) (pos? unread-chat-count))
             [:i.unseen-eids
              (str unread-chat-count)])])))))

(defn volume-icon [level color-class]
  (common/icon (common/volume-icon-kw level)
               {:path-props {:className color-class}}))

(defn viewers [app owner]
  (reify
    om/IDisplayName (display-name [_] "Hud Viewers")
    om/IInitState (init-state [_] {:editing-name? false
                                   :new-name ""})
    om/IDidMount (did-mount [_] (fdb/watch-doc-name-changes owner))
    om/IDidUpdate
    (did-update [_ _ prev-state]
      (when (and (not (:editing-name? prev-state))
                 (om/get-state owner :editing-name?)
                 (om/get-node owner "name-edit"))
        (.focus (om/get-node owner "name-edit"))
        (.select (goog.dom.Range/createFromNodeContents (om/get-node owner "name-edit")))))
    om/IRenderState
    (render-state [_ {:keys [editing-name? new-name]}]
      (let [{:keys [cast! db]} (om/get-shared owner)
            chat-opened? (get-in app state/chat-opened-path)
            client-id (:client-id app)
            viewers-count (count (remove (comp :hide-in-list? last) (get-in app [:subscribers :info])))
            can-edit? (not (empty? (:cust app)))
            show-viewers? (and (not (overlay-visible? app))
                               (get-in app state/viewers-opened-path))
            self-color (colors/find-color (get-in app [:cust-data :uuid->cust]) (get-in app [:cust :cust/uuid]) client-id)
            self-name (get-in app [:cust-data :uuid->cust (get-in app [:cust :cust/uuid]) :cust/name])
            doc (doc-model/find-by-id @db (:document/id app))]
        (html
         [:div.viewers {:key "viewers"}
          (when show-viewers?
            [:div.viewers-list
             [:div.viewers-list-frame
              (let [sub (get-in app [:subscribers :info client-id])
                    show-mouse? (:show-mouse? sub)]
                [:div.viewer.viewer-self {:class (when editing-name? "busy")}
                 [:div.viewer-avatar.viewer-tag
                  {:on-mouse-down #(let [color (colors/next-color colors/color-idents self-color)]
                                     (when (utils/logged-in? owner)
                                       (cast! :self-updated {:color color})
                                       (utils/stop-event %)))
                   :title (if (utils/logged-in? owner)
                            "Change your color."
                            "Login to change your color.")
                   :key self-color}
                  (common/icon :user (when show-mouse? {:path-props {:className (name self-color)}}))]
                 [:a.viewer-name.viewer-tag
                  (let [submit-fn #(do (when-not (str/blank? (om/get-state owner :new-name))
                                         (cast! :self-updated {:name (om/get-state owner :new-name)}))
                                     (om/set-state! owner :editing-name? false)
                                     (om/set-state! owner :new-name ""))]
                    (merge
                      (if can-edit?
                        {:on-click #(do
                                      (om/set-state! owner :editing-name? true)
                                      (.stopPropagation %))}
                        {:href (urls/overlay-path doc "username")
                         :role "button"})
                      {:ref "name-edit"
                       :title "Edit your display name."
                       :content-editable (if editing-name? true false)
                       :spell-check false
                       :on-key-down #(do
                                       (when (= "Enter" (.-key %))
                                         (.preventDefault %)
                                         (submit-fn)
                                         (utils/stop-event %))
                                       (when (= "Escape" (.-key %))
                                         (om/set-state! owner :editing-name? false)
                                         (om/set-state! owner :new-name "")
                                         (utils/stop-event %)))
                       :on-blur #(do (submit-fn)
                                   (utils/stop-event %))
                       :on-input #(om/set-state-nr! owner :new-name (goog.dom/getRawTextContent (.-target %)))}))
                  (or self-name "You")]
                 [:div.viewer-controls {:class (when (:recording sub) " recording ")}
                  (if (:recording sub)
                    [:a.viewer-toggle {:on-click #(cast! :recording-toggled)
                                       :role "button"
                                       :title "Disable your voice chat."
                                       :key "self-recording"}
                     (volume-icon (get-in sub [:recording :media-stream-volume] 0) (name self-color))]

                    [:a.viewer-toggle {:on-click #(cast! :recording-toggled)
                                       :role "button"
                                       :title "Enable your voice chat."
                                       :key "self-not-recording"}
                     (common/icon :sound-mute)])]])
              (for [[id {:keys [show-mouse? color cust-name hide-in-list? stream] :as sub}] (dissoc (get-in app [:subscribers :info]) client-id)
                    :when (not hide-in-list?)
                    :let [id-str (get-in app [:cust-data :uuid->cust (:cust/uuid sub) :cust/name] (apply str (take 6 id)))
                          color-class (name (colors/find-color (get-in app [:cust-data :uuid->cust])
                                                               (:cust/uuid sub)
                                                               (:client-id sub)))]]
                [:div.viewer
                 [:div.viewer-avatar.viewer-tag
                  (common/icon :user (when show-mouse? {:path-props {:className color-class}}))]
                 [:a.viewer-name.viewer-tag {:key id
                                             :on-click #(cast! :chat-user-clicked {:id-str id-str})
                                             :role "button"
                                             :title "Ping this viewer in chat."}
                  id-str]
                 [:div.viewer-controls {:class (when (:recording sub) "recording")}

                  ;; Holding off on showing other users' sound icons until prompts are ready
                  ;; (if (:recording sub)

                  (when (:recording sub)
                    [:a.viewer-toggle {;:on-click #(cast! :recording-toggled)
                                       :role "button"
                                       :title "Mute this collaborator."
                                       :key (str id-str "-recording")}
                     (volume-icon (get-in sub [:recording :media-stream-volume] 0) color-class)]

                    ;; Holding off on showing other users' sound icons until prompts are ready
                    ;; [:a.viewer-toggle {;:on-click #(cast! :recording-toggled)
                    ;;                    :role "button"
                    ;;                    :title "Request voice chat."
                    ;;                    :key (str id-str "-not-recording")}
                    ;;  (common/icon :sound-mute)]

                    )]])

              [:a.viewer.viewer-add {:href (urls/overlay-path doc "sharing")
                                     :class (when (< 3 viewers-count) "sink")
                                     :title "Invite."}
               [:i.viewer-avatar.viewer-tag
                (common/icon :plus)]
               [:span.viewer-name.viewer-tag
                "Invite"]]]])

          [:a.hud-viewers.hud-item.hud-toggle {:on-click (if show-viewers?
                                                           #(cast! :viewers-closed)
                                                           #(cast! :viewers-opened))
                                               :on-touch-end #(do
                                                                (.preventDefault %)
                                                                (if show-viewers?
                                                                  (cast! :viewers-closed)
                                                                  (if chat-opened?
                                                                    (do
                                                                      (cast! :chat-toggled)
                                                                      (cast! :viewers-opened))
                                                                    (cast! :viewers-opened))))
                                               :class (when show-viewers? "close")
                                               :data-count viewers-count
                                               :role "button"}
           (when-not show-viewers? (common/icon :user))]])))))

(defn hud [app owner]
  (reify
    om/IDisplayName (display-name [_] "Hud")
    om/IRender
    (render [_]
      (html
       [:div.hud
        (om/build viewers (utils/select-in app [state/chat-opened-path
                                                state/viewers-opened-path
                                                state/overlays-path
                                                [:subscribers :info]
                                                [:cust-data]
                                                [:show-viewers?]
                                                [:client-id]
                                                [:cust]
                                                [:mouse-type]
                                                [:cust-data]
                                                [:document/id]])
                  {:react-key "viewers"})
        (om/build menu (utils/select-in app [state/main-menu-learned-path
                                             state/overlays-path
                                             [:document/id]])
                  {:react-key "menu"})
        (when (utils/logged-in? owner)
          (om/build roster (utils/select-in app [state/main-menu-learned-path
                                                 state/overlays-path
                                                 [:document/id]
                                                 [:team]])
                    {:react-key "roster"}))
        (om/build chat (utils/select-in app [state/chat-opened-path
                                             state/viewers-opened-path
                                             state/chat-button-learned-path
                                             state/browser-settings-path
                                             [:document/id]
                                             [:show-viewers?]])
                  {:react-key "chat"})

        (om/build tray (utils/select-in app [state/chat-opened-path
                                             state/info-button-learned-path
                                             state/welcome-info-learned-path
                                             [:document/id]
                                             [:cust]
                                             [:max-document-scope]
                                             (state/doc-tx-rejected-count-path (:document/id app))])
                  {:react-key "tray"})

        ]))))
