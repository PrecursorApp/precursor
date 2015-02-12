(ns frontend.components.hud
  (:require [clojure.string :as str]
            [datascript :as d]
            [frontend.components.common :as common]
            [frontend.models.chat :as chat-model]
            [frontend.overlay :refer [current-overlay overlay-visible? overlay-count]]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [sablono.core :refer (html)])
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
            :on-touch-end #(do
                             (.preventDefault %)
                             (if (overlay-visible? app)
                               (cast! :overlay-menu-closed)
                               (cast! :main-menu-opened)))
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
            :on-touch-end #(do
                             (.preventDefault %)
                             (cast! :chat-toggled))
            :class (when-not chat-opened? "open")
            :role "button"
            :data-left (when-not chat-button-learned? (if chat-opened? "Close Chat" "Open Chat"))
            :title     (when     chat-button-learned? (if chat-opened? "Close Chat" "Open Chat"))}
           (common/icon :chat-morph)
           (when (and (not chat-opened?) (pos? unread-chat-count))
             [:i.unseen-eids
              (str unread-chat-count)])])))))

(defn viewers [app owner]
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
        (let [{:keys [cast! db]} (om/get-shared owner)
              client-id (:client-id app)
              viewers-count (count (remove (comp :hide-in-list? last) (:subscribers app)))
              can-edit? (not (empty? (:cust app)))
              viewers-truncated? (< 4 viewers-count)
              show-viewers? (and (not (overlay-visible? app))
                                 (get app :show-viewers? (< 1 viewers-count 5)))]
          (html
            [:div.viewers.hud-item
             {:class (when viewers-truncated? ["truncated"])}
             (when show-viewers?
               [:div.viewers-list
                [:div.viewers-list-frame
                 (let [show-mouse? (get-in app [:subscribers client-id :show-mouse?])]
                   [:div.viewer.viewer-self
                    [:div.viewer-avatar.viewer-tag
                     (if (= :touch (get-in app [:mouse :type]))
                       (common/icon :phone (when show-mouse? {:path-props {:style {:stroke (get-in app [:subscribers client-id :color])}}}))
                       (common/icon :user (when show-mouse? {:path-props {:style {:stroke (get-in app [:subscribers client-id :color])}}})))]
                    (if editing-name?
                      [:form.viewer-name-form
                       {:on-submit #(do (when-not (str/blank? new-name)
                                          (cast! :self-updated {:name new-name}))
                                        (om/set-state! owner :editing-name? false)
                                        (om/set-state! owner :new-name "")
                                        (utils/stop-event %))
                        :on-blur #(do (when-not (str/blank? new-name)
                                        (cast! :self-updated {:name new-name}))
                                      (om/set-state! owner :editing-name? false)
                                      (om/set-state! owner :new-name "")
                                      (utils/stop-event %))
                        :on-key-down #(when (= "Escape" (.-key %))
                                        (om/set-state! owner :editing-name? false)
                                        (om/set-state! owner :new-name "")
                                        (utils/stop-event %))}
                       [:input.viewer-name-input
                        {:type "text"
                         :ref "name-edit"
                         :tab-index 1
                         ;; TODO: figure out why we need value here
                         :value new-name
                         :on-change #(om/set-state! owner :new-name (.. % -target -value))}]]

                      [:div.viewer-name.viewer-tag (or (get-in app [:cust :cust/name]) "You")])
                    [:div.viewer-knobs
                     [:a.viewer-knob
                      {:key client-id
                       :on-click #(do
                                    (if can-edit?
                                      (om/set-state! owner :editing-name? true)
                                      (cast! :overlay-username-toggled))
                                    (.stopPropagation %))
                       :role "button"
                       :title "Edit your display name."}
                      (common/icon :pencil)]]])
                 (for [[id {:keys [show-mouse? color cust-name hide-in-list?]}] (dissoc (:subscribers app) client-id)
                       :when (not hide-in-list?)
                       :let [id-str (or cust-name (apply str (take 6 id)))]]
                   [:div.viewer
                    [:div.viewer-avatar.viewer-tag
                     (common/icon :user (when show-mouse? {:path-props {:style {:stroke color}}}))]
                    [:div.viewer-name.viewer-tag
                     id-str]
                    [:div.viewer-knobs
                     [:a.viewer-knob
                      {:key id
                       :on-click #(cast! :chat-user-clicked {:id-str id-str})
                       :role "button"
                       :title "Ping this viewer in chat."}
                      (common/icon :at)]]])]])
             [:a.hud-viewers.hud-item.hud-toggle
              {:on-click (if show-viewers?
                           #(cast! :viewers-closed)
                           #(cast! :viewers-opened))
               :on-touch-end #(do
                                (.preventDefault %)
                                (if show-viewers?
                                  (cast! :viewers-closed)
                                  (cast! :viewers-opened)))
               :class (when show-viewers? "close")
               :data-count (when (< 1 viewers-count) viewers-count)
               :role "button"}
              (common/icon :times)
              (common/icon :users)]])))))

(defn hud [app owner]
  (reify
    om/IRender
    (render [_]
      (html
        [:div.hud
         (om/build viewers app)
         (om/build menu app)
         (om/build chat app)
         (om/build mouse app)

         ;; TODO finish this button once landing and outer are done
         ;; (om/build landing app)

         ;; deciding whether to get rid of this
         ;; (when-not (:cust app)
         ;;   (om/build info app))

         ]))))
