(ns frontend.components.chat
  (:require [cljs-time.core :as time]
            [cljs-time.format :as time-format]
            [clojure.set :as set]
            [clojure.string :as str]
            [datascript :as d]
            [frontend.colors :as colors]
            [frontend.cursors :as cursors]
            [frontend.async :refer [put!]]
            [frontend.components.common :as common]
            [frontend.datascript :as ds]
            [frontend.datetime :as datetime]
            [frontend.models.chat :as chat-model]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [goog.date]
            [goog.dom]
            [goog.string :as gstring]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [sablono.core :refer (html)])
  (:import [goog.ui IdGenerator]))

(def url-regex #"(?im)\b(?:https?|ftp)://[-A-Za-z0-9+@#/%?&=~_|!:,.;]*[-A-Za-z0-9+@#/%=~_|]")

(defn linkify [text]
  (let [matches (re-seq url-regex text)
        ;; may need to add [""], split can return empty array
        parts (or (seq (str/split text url-regex)) [""])]
    (reduce (fn [acc [pre url]]
              (conj acc
                    (when (seq pre) [:span pre])
                    (when url [:a {:href url :target "_blank"} url])))
            [:span] (partition-all 2 (concat (interleave parts
                                                         matches)
                                             (when (not= (count parts)
                                                         (count matches))
                                               [(last parts)]))))))


(defn chat-item [{:keys [chat uuid->cust show-sender?]} owner {:keys [sente-id]}]
  (reify
    om/IDisplayName (display-name [_] "Chat Item")
    om/IRender
    (render [_]
      (let [id (apply str (take 6 (str (:session/uuid chat))))
            cust-name (or (get-in uuid->cust [(:cust/uuid chat) :cust/name])
                          (chat-model/display-name chat sente-id))
            chat-body (if (string? (:chat/body chat))
                        (linkify (:chat/body chat))
                        (:chat/body chat))
            short-time (datetime/short-time (js/Date.parse (:server/timestamp chat)))
            color-class (name (colors/find-color uuid->cust (:cust/uuid chat) (:session/uuid chat)))]
        (html [:div.chat-message {:key (str "chat-message" (:db/id chat))}
               (when show-sender?
                 [:div.message-head
                  [:div.message-avatar.hide-from-menu (common/icon :user {:path-props {:className color-class}})]
                  [:div.message-author cust-name]
                  [:div.message-time short-time]])
               [:div.message-body
                chat-body]])))))

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

(defn input [app owner]
  (reify
    om/IDisplayName (display-name [_] "Chat Input")
    om/IInitState (init-state [_] {:chat-height 64})
    om/IRenderState
    (render-state [_ {:keys [chat-height]}]
      (let [{:keys [cast!]} (om/get-shared owner)
            chat-opened? (get-in app state/chat-opened-path)
            chat-submit-learned? (get-in app state/chat-submit-learned-path)
            submit-chat (fn [e]
                          (cast! :chat-submitted)
                          (om/set-state! owner :chat-height 64)
                          (utils/stop-event e))
            chats (cursors/observe-chats owner)
            now (js/Date.)
            chatting (filter (fn [info]
                               (and (seq (:chat-body info))
                                    (> (* 1000 30) (- (.getTime now)
                                                      (.getTime (:last-update info))))))
                             (vals chats))]
        (html
         [:form.chat-box {:on-submit submit-chat
                          :on-key-down #(when (and (= "Enter" (.-key %))
                                                   (not (.-shiftKey %))
                                                   (not (.-ctrlKey %))
                                                   (not (.-metaKey %))
                                                   (not (.-altKey %)))
                                          (submit-chat %))}
          [:textarea.chat-input {:tab-index "1"
                                 :ref "chat-body"
                                 :disabled (if (or (not chat-opened?)
                                                   (:show-landing? app)) true false)
                                 :id "chat-input"
                                 :style {:height chat-height}
                                 :required true
                                 :value (or (get-in app [:chat :body])
                                            "")
                                 :on-change #(let [node (.-target %)]
                                               (cast! :chat-body-changed {:chat-body (.-value node)})
                                               (when (not= (.-scrollHeight node) (.-clientHeight node))
                                                 (om/set-state! owner :chat-height (max 64 (.-scrollHeight node)))))}]
          (if chat-submit-learned?
            (list
              (when (seq chatting)
                [:label.chat-typing-notice {:data-typing-notice (let [uuid->cust (get-in app [:cust-data :uuid->cust])
                                                                    cust-names (reduce (fn [acc chatter]
                                                                                         (conj acc
                                                                                               (gstring/truncate
                                                                                                 (or (get-in uuid->cust [(:cust/uuid chatter) :cust/name])
                                                                                                     (apply str (take 6 (:client-id chatter))))
                                                                                                 10)))
                                                                                       #{} chatting)
                                                                    name-string (str/join ", " (sort cust-names))]
                                                                (str (if (> (count name-string) 30)
                                                                       ;; show number of people chatting if it starts to get too long
                                                                       (str (count cust-names) " people")
                                                                       name-string)
                                                                     (if (= 1 (count cust-names))
                                                                       " is "
                                                                       " are ")
                                                                     "typing..."))}])

              [:label.chat-placeholder.hide-from-menu {:data-before "Chat."}])
            [:label.chat-teach-enter.hide-from-menu {:data-step-1 "Click here."
                                                   :data-step-2 "Type something."
                                                   :data-step-3 "Send with enter."
                                                   :data-remind "Don't forget to hit enter."}])])))))

(defn log [{:keys [sente-id client-id] :as app} owner]
  (reify
    om/IDisplayName (display-name [_] "Chat Log")
    om/IInitState
    (init-state [_]
      {:listener-key (.getNextUniqueId (.getInstance IdGenerator))
       :auto-scroll? true
       :touch-enabled? false})
    om/IDidMount
    (did-mount [_]
      (om/set-state! owner :touch-enabled? (.hasOwnProperty js/window "ontouchstart"))
      (d/listen! (om/get-shared owner :db)
                 (om/get-state owner :listener-key)
                 (fn [tx-report]
                   ;; TODO: better way to check if state changed
                   (when-let [chat-datoms (seq (filter #(or (= :chat/body (:a %))
                                                            (= :server/timestamp (:a %))
                                                            (= :document/chat-bot (:a %)))
                                                       (:tx-data tx-report)))]
                     (om/refresh! owner)))))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :db) (om/get-state owner :listener-key)))
    om/IWillUpdate
    (will-update [_ next-props next-state]
      ;; check for scrolled all of the way down
      (let [node (om/get-node owner "chat-messages")
            em 16 ;; extra padding for the scroll
            auto-scroll? (<= (- (.-scrollHeight node) (.-scrollTop node) em)
                             (.-clientHeight node))]
        (when (not= (om/get-state owner :auto-scroll?) auto-scroll?)
          (om/set-state! owner :auto-scroll? auto-scroll?))))
    om/IDidUpdate
    (did-update [_ _ _]
      (when (om/get-state owner :auto-scroll?)
        (set! (.-scrollTop (om/get-node owner "chat-messages"))
              10000000)))
    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)
            chats (ds/touch-all '[:find ?t :where [?t :chat/body]] @db)
            chat-bot (:document/chat-bot (d/entity @db (ffirst (d/q '[:find ?t :where [?t :document/name]] @db))))
            dummy-chat {:chat/body [:span
                                    "Welcome, try the "
                                    [:a {:href "https://precursorapp.com/document/17592197661008" :target "_blank"}
                                     "how-to"]
                                    " doc, see other users "
                                    [:a {:href "/blog/ideas-are-made-with-precursor" :target "_blank"}
                                     "make"]
                                    " things, or ask us "
                                    [:a {:on-click #(cast! :chat-user-clicked {:id-str (:chat-bot/name chat-bot)}) :role "button"}
                                     "anything"]
                                    "!"]
                        :cust/uuid (:cust/uuid state/subscriber-bot)
                        :server/timestamp (js/Date.)}]
        (html
          [:div.chat-log
           [:div.chat-messages {:ref "chat-messages"}
            (when chat-bot
              (om/build chat-item {:chat dummy-chat
                                   :uuid->cust {(:cust/uuid state/subscriber-bot)
                                                (merge
                                                  (select-keys state/subscriber-bot [:cust/color-name :cust/uuid])
                                                  {:cust/name (:chat-bot/name chat-bot)})}
                                   :show-sender? true}))
            (let [chat-groups (group-by #(date->bucket (:server/timestamp %)) chats)]
              (for [[time chat-group] (sort-by #(:server/timestamp (first (second %)))
                                               chat-groups)]

                (list (when (or (not= 1 (count chat-groups))
                                (not= #{"Today"} (set (keys chat-groups))))
                        [:div.chat-date.divider-small time])
                      (for [[prev-chat chat] (partition 2 1 (concat [nil] (sort-by :server/timestamp chat-group)))]
                        (om/build chat-item {:chat chat
                                             :uuid->cust (get-in app [:cust-data :uuid->cust])
                                             :show-sender? (or (not= (chat-model/display-name prev-chat sente-id)
                                                                     (chat-model/display-name chat sente-id))

                                                               (or (not (:server/timestamp chat))
                                                                   (not (:server/timestamp prev-chat))
                                                                   (< (* 1000 60 5) (- (.getTime (:server/timestamp chat))
                                                                                       (.getTime (:server/timestamp prev-chat))))))}
                                  {:react-key (:db/id chat)
                                   :opts {:sente-id sente-id}})))))]])))))

(defn chat [app owner]
  (reify
    om/IDisplayName (display-name [_] "Chat")
    om/IDidMount
    (did-mount [_]
      ;; this needs to be here so that we can account for chat :(
      ((om/get-shared owner :cast!)
       :handle-camera-query-params
       (select-keys (get-in app [:navigation-data :query-params])
                    [:x :y :z :cx :cy])))
    om/IRender
    (render [_]
      (let [{:keys [cast!]} (om/get-shared owner)
            controls-ch (om/get-shared owner [:comms :controls])
            client-id (:client-id app)
            chat-opened? (get-in app state/chat-opened-path)
            chat-mobile-open? (get-in app state/chat-mobile-opened-path)
            document-id (get-in app [:document/id])]
        (html
         [:div.chat
          [:div#canvas-size.chat-offset.holo
           [:svg {:width "100%" :height "100%"}
            [:defs
             [:mask#canvas-mask
              [:rect {:width "100%" :height "100%" :fill "#fff"}]]]]]
          [:div.chat-window {:class (when (or (not chat-opened?)
                                              (:show-landing? app)) ["closed"])}
           (om/build log (utils/select-in app [[:document/id]
                                               [:sente-id]
                                               [:client-id]
                                               [:cust-data]]))
           (om/build input (utils/select-in app [state/chat-opened-path
                                                 state/chat-submit-learned-path
                                                 [:show-landing?]
                                                 [:chat]
                                                 [:cust-data]]))]])))))
