(ns frontend.components.aside
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datascript :as d]
            [frontend.async :refer [put!]]
            [frontend.components.common :as common]
            [frontend.datascript :as ds]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]])
  (:import [goog.ui IdGenerator]))

(defn chat-aside [{:keys [db chat-body client-uuid aside-menu-opened chat-bot]} owner]
  (reify
    om/IInitState
    (init-state [_]
      {:listener-key (.getNextUniqueId (.getInstance IdGenerator))
       :touch-enabled? false})
    om/IDidMount
    (did-mount [_]
      (om/set-state! owner :touch-enabled? (.hasOwnProperty js/window "ontouchstart"))
      (d/listen! (om/get-shared owner :db)
                 (om/get-state owner :listener-key)
                 (fn [tx-report]
                   ;; TODO: better way to check if state changed
                   (when-let [chat-datoms (seq (filter #(= :chat/body (:a %)) (:tx-data tx-report)))]
                     (om/refresh! owner)))))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :db) (om/get-state owner :listener-key)))
    om/IWillUpdate
    (will-update [_ _ _]
      ;; check for scrolled all of the way down
      (let [node (om/get-node owner "chat-messages")]
        (om/set-state! owner :auto-scroll (= (- (.-scrollHeight node) (.-scrollTop node))
                                             (.-clientHeight node)))))
    om/IDidUpdate
    (did-update [_ _ _]
      (when (om/get-state owner :auto-scroll)
        (set! (.-scrollTop (om/get-node owner "chat-messages"))
              10000000)))
    om/IRender
    (render [_]
      (let [{:keys [cast!]} (om/get-shared owner)
            chats (ds/touch-all '[:find ?t :where [?t :chat/body]] @db)
            dummy-chat {:chat/body [:span "Welcome to Precursor! "
                                          "Create fast prototypes and share your url to collaborate. "
                                          "Chat "
                                          [:a {:on-click #(cast! :aside-user-clicked {:id-str (str/lower-case chat-bot)})
                                               :role "button"}
                                           (str "@" (str/lower-case chat-bot))]
                                          " for help."]
                        :chat/color "#00b233"
                        :session/uuid chat-bot
                        :server/timestamp (js/Date. 0)}]
        (html
         [:section.aside-chat
          [:div.chat-messages {:ref "chat-messages"}
           (for [chat (sort-by :server/timestamp (concat chats [dummy-chat]))
                 :let [id (apply str (take 6 (str (:session/uuid chat))))
                       name (or (:chat/cust-name chat)
                                (if (= (str (:session/uuid chat))
                                       client-uuid)
                                  "You"
                                  id))]]
             (html [:div.message {:key (:db/id chat)}
                    [:span {:style {:color (or (:chat/color chat) (str "#" id))}}
                     name]
                    " "
                    (:chat/body chat)]))]
          [:form {:on-submit #(do (cast! :chat-submitted)
                                  false)
                  :on-key-down #(when (and (= "Enter" (.-key %))
                                           (not (.-shiftKey %))
                                           (not (.-ctrlKey %))
                                           (not (.-metaKey %))
                                           (not (.-altKey %)))
                                  (cast! :chat-submitted)
                                  false)}
           [:textarea {:id "chat-box"
                       :tab-index "1"
                       :type "text"
                       :value (or chat-body "")
                       :placeholder "Send a message..."
                       :on-change #(cast! :chat-body-changed {:value (.. % -target -value)})}]]])))))

(defn menu [app owner]
  (reify
    om/IInitState (init-state [_] {:editing-name? false
                                   :new-name ""})
    om/IDidUpdate
    (did-update [_ _ _]
      (when (and (om/get-state owner :editing-name?)
                 (om/get-node owner "name-edit"))
        (.focus (om/get-node owner "name-edit"))))
    om/IRenderState
    (render-state [_ {:keys [editing-name? new-name]}]
      (let [{:keys [cast!]} (om/get-shared owner)
            controls-ch (om/get-shared owner [:comms :controls])
            client-id (:client-uuid app)
            aside-opened? (get-in app state/aside-menu-opened-path)
            chat-mobile-open? (get-in app state/chat-mobile-opened-path)
            document-id (get-in app [:document/id])
            can-edit? (not (empty? (:cust app)))
            change-username-learned? (get-in app state/change-username-learned-path)]
        (html
         [:aside.app-aside {:class (concat
                                    (when-not aside-opened? ["closed"])
                                    (if chat-mobile-open? ["show-chat-on-mobile"] ["show-people-on-mobile"]))
                            :style {:width (if aside-opened?
                                             (get-in app state/aside-width-path)
                                             0)}}
          [:button.aside-switcher {:on-click #(cast! :chat-mobile-toggled)
                                   ;; :class (if chat-mobile-open? "chat-mobile" "people-mobile")
                                   }
           [:span.aside-switcher-option {:class (when-not chat-mobile-open? "toggled")} "People"]
           [:span.aside-switcher-option {:class (when     chat-mobile-open? "toggled")} "Chat"]]
          [:section.aside-people
           (let [show-mouse? (get-in app [:subscribers client-id :show-mouse?])]
             [:a.people-you {:key client-id
                  :data-bottom (when (and can-edit? (not change-username-learned?)) "Click to edit")
                  :role "button"
                  :on-click #(if can-edit?
                               (om/set-state! owner :editing-name? true)
                               (cast! :overlay-username-toggled))}
              (common/icon :user (when show-mouse? {:path-props
                                                    {:style
                                                     {:stroke (get-in app [:subscribers client-id :color])}}}))

              (if editing-name?
                [:form {:on-submit #(do (cast! :self-updated {:name new-name})
                                        (om/set-state! owner :editing-name? false)
                                        false)
                        :on-blur #(do (cast! :self-updated {:name new-name})
                                      (om/set-state! owner :editing-name? false)
                                      false)
                        :on-key-down #(when (= "Escape" (.-key %))
                                        (om/set-state! owner :editing-name? false)
                                        (om/set-state! owner :new-name "")
                                        false)}
                 [:input {:type "text"
                          :ref "name-edit"
                          :tab-index 1
                          :on-change #(om/set-state! owner :new-name (.. % -target -value))}]]
                [:span (or (get-in app [:cust :name]) "You")])])
           (for [[id {:keys [show-mouse? color cust-name]}] (dissoc (:subscribers app) client-id)
                 :let [id-str (or cust-name (apply str (take 6 id)))]]
             [:a {:title "An anonymous user is viewing this document. Click to toggle showing their mouse position."
                  :role "button"
                  :key id
                  :on-click #(cast! :aside-user-clicked {:id-str id-str})}
              (common/icon :user (when show-mouse? {:path-props {:style {:stroke color}}}))
              [:span id-str]])]
          ;; XXX better name here
          (om/build chat-aside {:db (:db app)
                                :client-uuid (:client-uuid app)
                                :chat-body (get-in app [:chat :body])
                                :chat-bot (get-in app (state/doc-chat-bot-path document-id))
                                :aside-menu-opened (get-in app state/aside-menu-opened-path)})])))))
