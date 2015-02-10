(ns frontend.components.hud
  (:require [datascript :as d]
            [frontend.components.common :as common]
            [frontend.models.chat :as chat-model]
            [frontend.overlay :refer [current-overlay overlay-visible? overlay-count]]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]])
  (:import [goog.ui IdGenerator]))

(defn mouse [app owner]
  (reify
    om/IRender
    (render [_]
      (html
        [:div.mouse-stats.hud-item.noninteractive
         (if (:mouse app)
           (pr-str (select-keys (:mouse app) [:x :y :rx :ry]))
           "{:x 0, :y 0, :rx 0, :ry 0}")]))))

(defn menu [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            main-menu-learned? (get-in app state/main-menu-learned-path)]
        (html
          [:a.hud-menu.hud-item.hud-toggle.menu-needed
           {:on-click (if (overlay-visible? app)
                        #(cast! :overlay-menu-closed)
                        #(cast! :main-menu-opened))
            :role "button"
            :class (when (overlay-visible? app)
                     (concat
                       ["bkg-light"]
                       (if (< 1 (overlay-count app))
                         ["back"]
                         ["close"])))
            :data-right (when-not main-menu-learned?
                          (if (overlay-visible? app) "Close Menu" "Open Menu"))
            :title (when main-menu-learned?
                     (if (overlay-visible? app) "Close Menu" "Open Menu"))}
           (common/icon :menu)])))))

(defn info [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            info-button-learned? (get-in app state/info-button-learned-path)]
        (html
          [:a.hud-info.hud-item.hud-toggle
           {:on-click #(cast! :overlay-info-toggled)
            :role "button"
            :class (when-not info-button-learned? "hover")
            :data-right (when-not info-button-learned? "What is Precursor?")
            :title (when info-button-learned? "What is Precursor?")}
           (common/icon :info)])))))

(defn landing [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            info-button-learned? (get-in app state/info-button-learned-path)]
        (html
          [:a.hud-landing.hud-item.hud-toggle
           {:on-click #(cast! :landing-opened)
            :role "button"}
           (common/icon :info)])))))

(defn chat [app owner]
  (reify
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
            last-read-time (get-in app (state/last-read-chat-time-path (:document/id app)))
            dummy-chat? (seq (d/datoms @db :aevt :document/chat-bot))
            unread-chat-count (chat-model/compute-unread-chat-count @db last-read-time)
            unread-chat-count (if last-read-time
                                unread-chat-count
                                ;; add one for the dummy message
                                (+ (if dummy-chat? 1 0) unread-chat-count))]
        (html
          [:a.hud-chat.hud-item.hud-toggle
           {:on-click #(cast! :chat-toggled)
            :class (when-not chat-opened? "open")
            :role "button"
            :data-left (when-not chat-button-learned? (if chat-opened? "Close Chat" "Open Chat"))
            :title     (when     chat-button-learned? (if chat-opened? "Close Chat" "Open Chat"))}
           (common/icon :chat)
           (when (and (not chat-opened?) (pos? unread-chat-count))
             [:i.unseen-eids
              (str unread-chat-count)])])))))

(defn viewers [app owner]
  (reify
    om/IRender
    (render [_]
      (html
        (let [client-id (:client-id app)]

          ;; TODO deciding whether to edit name inline or not...
          ;; [:section.chat-people
          ;;  (let [show-mouse? (get-in app [:subscribers client-id :show-mouse?])]
          ;;    [:a.people-you {:key client-id
          ;;                    :data-bottom (when-not (get-in app [:cust :cust/name]) "Click to edit")
          ;;                    :role "button"
          ;;                    :on-click #(if can-edit?
          ;;                                 (om/set-state! owner :editing-name? true)
          ;;                                 (cast! :overlay-username-toggled))}
          ;;     (common/icon :user (when show-mouse? {:path-props
          ;;                                           {:style
          ;;                                            {:stroke (get-in app [:subscribers client-id :color])}}}))

          ;;     (if editing-name?
          ;;       [:form {:on-submit #(do (when-not (str/blank? new-name)
          ;;                                 (cast! :self-updated {:name new-name}))
          ;;                               (om/set-state! owner :editing-name? false)
          ;;                               (om/set-state! owner :new-name "")
          ;;                               (utils/stop-event %))
          ;;               :on-blur #(do (when-not (str/blank? new-name)
          ;;                               (cast! :self-updated {:name new-name}))
          ;;                             (om/set-state! owner :editing-name? false)
          ;;                             (om/set-state! owner :new-name "")
          ;;                             (utils/stop-event %))
          ;;               :on-key-down #(when (= "Escape" (.-key %))
          ;;                               (om/set-state! owner :editing-name? false)
          ;;                               (om/set-state! owner :new-name "")
          ;;                               (utils/stop-event %))}
          ;;        [:input {:type "text"
          ;;                 :ref "name-edit"
          ;;                 :tab-index 1
          ;;                 ;; TODO: figure out why we need value here
          ;;                 :value new-name
          ;;                 :on-change #(om/set-state! owner :new-name (.. % -target -value))}]]
          ;;       [:span (or (get-in app [:cust :cust/name]) "You")])])
          ;;  (for [[id {:keys [show-mouse? color cust-name hide-in-list?]}] (dissoc (:subscribers app) client-id)
          ;;        :when (not hide-in-list?)
          ;;        :let [id-str (or cust-name (apply str (take 6 id)))]]
          ;;    [:a {:title "Ping this person in chat."
          ;;         :role "button"
          ;;         :key id
          ;;         :on-click #(cast! :chat-user-clicked {:id-str id-str})}
          ;;     (common/icon :user (when show-mouse? {:path-props {:style {:stroke color}}}))
          ;;     [:span id-str]])]

          [:div.viewers {:class (when (< 4 (count (:subscribers app))) "overflowing")}
           (let [show-mouse? (get-in app [:subscribers client-id :show-mouse?])]
             [:div.viewer.viewer-self
              (common/icon :user (when show-mouse? {:path-props
                                                    {:style
                                                     {:stroke (get-in app [:subscribers client-id :color])}}}))
              [:div.viewer-name
               {:data-count (count (:subscribers app))
                ;:title "X and X others are viewing this doc."
                ; :data-name (or (get-in app [:cust :name]) "You")
                }
               (or (get-in app [:cust :name]) "You")]])
           (for [[id {:keys [show-mouse? color cust-name hide-in-list?]}] (dissoc (:subscribers app) client-id)
                 :when (not hide-in-list?)
                 :let [id-str (or cust-name (apply str (take 6 id)))]]
             [:div.viewer {:key id}
              (common/icon :user (when show-mouse? {:path-props {:style {:stroke color}}}))
              [:div.viewer-name id-str]])])))))

(defn hud [app owner]
  (reify
    om/IRender
    (render [_]
      (html
        (let []
         [:div.hud
          (om/build viewers app)
          (om/build menu app)
          (om/build chat app)
          (om/build mouse app)

          ;; TODO finish this button once landing and outer are done
          (om/build landing app)

          ;; deciding whether to get rid of this
          ;; (when-not (:cust app)
          ;;   (om/build info app))

          ])))))
