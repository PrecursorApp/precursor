(ns frontend.components.chat
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [cljs-time.core :as time]
            [cljs-time.format :as time-format]
            [datascript :as d]
            [frontend.async :refer [put!]]
            [frontend.components.common :as common]
            [frontend.datascript :as ds]
            [frontend.datetime :as datetime]
            [frontend.models.chat :as chat-model]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [goog.date]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]])
  (:import [goog.ui IdGenerator]))

(def url-regex #"(?im)\b(?:https?|ftp)://[-A-Za-z0-9+@#/%?=~_|!:,.;]*[-A-Za-z0-9+@#/%=~_|]")

(defn linkify [text]
  (let [matches (re-seq url-regex text)
        ;; may need to add [""], split can return empty array
        parts (or (seq (str/split text url-regex)) [""])]
    (reduce (fn [acc [pre url]]
              (conj acc [:span pre] (when url [:a {:href url :target "_blank"} url])))
            [:span] (partition-all 2 (concat (interleave parts
                                                         matches)
                                             (when (not= (count parts)
                                                         (count matches))
                                               [(last parts)]))))))


(defn chat-item [chat owner {:keys [sente-id show-sender?]}]
  (reify
    om/IRender
    (render [_]
      (let [id (apply str (take 6 (str (:session/uuid chat))))
            name (chat-model/display-name chat sente-id)
            chat-body (if (string? (:chat/body chat))
                        (linkify (:chat/body chat))
                        (:chat/body chat))
            short-time (datetime/short-time (js/Date.parse (:server/timestamp chat)))]
        (html [:div.chat-message {:key (str "chat-message" (:db/id chat))}
               [:div.message-head
                (when show-sender?
                  (list
                   [:div.message-sender {:style {:color (or (:chat/color chat) (str "#" id))}} name]
                   [:div.message-time short-time]))]
               [:div.message-body
                [:div.message-content chat-body]]])))))

(def day-of-week
  {1 "Monday"
   2 "Tuesday"
   3 "Wednesday"
   4 "Thursday"
   5 "Friday"
   6 "Saturday"
   7 "Sunday"})

(def month-of-year
  {0 "Jan"
   1 "Feb"
   2 "Mar"
   3 "Apr"
   4 "May"
   5 "June"
   6 "July"
   7 "Aug"
   8 "Sep"
   9 "Oct"
   10 "Nov"
   11 "Dec"})

(defn date->bucket [date]
  (let [time (goog.date.DateTime. date)
        start-of-day (doto (goog.date.DateTime.)
                       (.setHours 0)
                       (.setMinutes 0)
                       (.setSeconds 0)
                       (.setMilliseconds 0))]
    (cond
     (time/after? time start-of-day) "Today"
     (time/after? time (time/minus start-of-day (time/days 1))) "Yesterday"
     (time/after? time (time/minus start-of-day (time/days 6))) (day-of-week (time/day-of-week time))
     :else (str (month-of-year (.getMonth time)) " " (.getDate time)))))

(defn chat [{:keys [db chat-body sente-id client-id chat-opened chat-bot]} owner]
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
            dummy-chat {:chat/body [:span
                                    "Welcome to Precursor! "
                                    "Create fast prototypes and share your url to collaborate. "
                                    "Chat "
                                    [:a {:on-click #(cast! :chat-user-clicked {:id-str (str/lower-case chat-bot)})
                                         :role "button"}
                                     (str "@" (str/lower-case chat-bot))]
                                    " for help."]
                        :chat/color "#00b233"
                        :session/uuid chat-bot
                        :server/timestamp (js/Date.)}]
        (html
         [:section.chat-log
          [:div.chat-messages {:ref "chat-messages"}
           (om/build chat-item dummy-chat {:opts {:show-sender? true}})
           (let [chat-groups (group-by #(date->bucket (:server/timestamp %)) chats)]
             (for [[time chat-group] (sort-by #(:server/timestamp (first (second %)))
                                              chat-groups)]

               (list (when (or (not= 1 (count chat-groups))
                               (not= #{"Today"} (set (keys chat-groups))))
                       [:h2.chat-date time])
                     (for [[prev-chat chat] (partition 2 1 (concat [nil] (sort-by :server/timestamp chat-group)))]
                       (om/build chat-item chat
                                 {:key :db/id
                                  :opts {:sente-id sente-id
                                         :show-sender? (not= (chat-model/display-name prev-chat sente-id)
                                                             (chat-model/display-name chat sente-id))}})))))]
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
            client-id (:client-id app)
            chat-opened? (get-in app state/chat-opened-path)
            chat-mobile-open? (get-in app state/chat-mobile-opened-path)
            document-id (get-in app [:document/id])
            can-edit? (not (empty? (:cust app)))]
        (html
         [:div.app-chat {:class (concat
                                 (when-not chat-opened? ["closed"])
                                 (if chat-mobile-open? ["show-chat-on-mobile"] ["show-people-on-mobile"]))}
          [:button.chat-switcher {:on-click #(cast! :chat-mobile-toggled)
                                  ;; :class (if chat-mobile-open? "chat-mobile" "people-mobile")
                                  }
           [:span.chat-switcher-option {:class (when-not chat-mobile-open? "toggled")} "People"]
           [:span.chat-switcher-option {:class (when     chat-mobile-open? "toggled")} "Chat"]]
          [:section.chat-people
           (let [show-mouse? (get-in app [:subscribers client-id :show-mouse?])]
             [:a.people-you {:key client-id
                             :data-bottom (when-not (get-in app [:cust :name]) "Click to edit")
                             :role "button"
                             :on-click #(if can-edit?
                                          (om/set-state! owner :editing-name? true)
                                          (cast! :overlay-username-toggled))}
              (common/icon :user (when show-mouse? {:path-props
                                                    {:style
                                                     {:stroke (get-in app [:subscribers client-id :color])}}}))

              (if editing-name?
                [:form {:on-submit #(do (when-not (str/blank? new-name)
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
                 [:input {:type "text"
                          :ref "name-edit"
                          :tab-index 1
                          ;; TODO: figure out why we need value here
                          :value new-name
                          :on-change #(om/set-state! owner :new-name (.. % -target -value))}]]
                [:span (or (get-in app [:cust :name]) "You")])])
           (for [[id {:keys [show-mouse? color cust-name]}] (dissoc (:subscribers app) client-id)
                 :let [id-str (or cust-name (apply str (take 6 id)))]]
             [:a {:title "Ping this person in chat."
                  :role "button"
                  :key id
                  :on-click #(cast! :chat-user-clicked {:id-str id-str})}
              (common/icon :user (when show-mouse? {:path-props {:style {:stroke color}}}))
              [:span id-str]])]
          ;; XXX better name here
          (om/build chat {:db (:db app)
                          :document/id (:document/id app)
                          :sente-id (:sente-id app)
                          :client-id (:client-id app)
                          :chat-body (get-in app [:chat :body])
                          :chat-bot (get-in app (state/doc-chat-bot-path document-id))
                          :chat-opened (get-in app state/chat-opened-path)})])))))
