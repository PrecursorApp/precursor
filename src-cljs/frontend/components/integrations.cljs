(ns frontend.components.integrations
  (:require [cljs.core.async :as async]
            [datascript :as d]
            [frontend.components.common :as common]
            [frontend.db :as fdb]
            [frontend.sente :as sente]
            [frontend.urls :as urls]
            [frontend.utils :as utils]
            [om.dom :as dom]
            [om.core :as om]
            [taoensso.sente])
  (:require-macros [sablono.core :refer (html)]
                   [cljs.core.async.macros :as am :refer [go]])
  (:import [goog.ui IdGenerator]))

(defn slack-form [app owner]
  (reify
    om/IDisplayName (display-name [_] "Slack Form")
    om/IInitState (init-state [_] {:channel-name nil :webhook-url nil})
    om/IRenderState
    (render-state [_ {:keys [channel-name webhook-url submitting? error]}]
      (let [submit-fn (fn []
                        (om/set-state! owner :submitting? true)
                        (go (let [resp (async/<! (sente/ch-send-msg (:sente app)
                                                                    [:team/create-slack-integration
                                                                     {:slack-hook/channel-name channel-name
                                                                      :slack-hook/webhook-url webhook-url
                                                                      :team/uuid (get-in app [:team :team/uuid])}]
                                                                    30000
                                                                    (async/promise-chan)))]
                              (if-not (taoensso.sente/cb-success? resp)
                                (om/update-state! owner (fn [s] (assoc s
                                                                       :submitting? false
                                                                       :error "The request timed out, please refresh and try again.")))
                                (do
                                  (om/update-state! owner #(assoc % :submitting? false :error nil))
                                  (if (= :success (:status resp))
                                    (do
                                      (om/update-state! owner #(assoc % :channel-name nil :webhook-url nil))
                                      (d/transact! (om/get-shared owner :team-db) (:entities resp)))
                                    (om/set-state! owner :error (:error-msg resp))))))))]
        (html
         [:div.content.make
          [:form {:on-submit submit-fn}
           [:div.adaptive.make
            [:input {:type "text"
                     :required "true"
                     :tabIndex 1
                     :value (or channel-name "")
                     :on-change #(om/set-state! owner :channel-name (.. % -target -value))
                     :on-key-down #(when (= "Enter" (.-key %))
                                     (.focus (om/get-node owner "webhook-input")))}]
            [:label {:data-label "Channel"
                     :data-placeholder "What's the channel name?"}]]
           [:div.adaptive.make
            [:input {:ref "webhook-input"
                     :type "text"
                     :required "true"
                     :tabIndex 1
                     :value (or webhook-url "")
                     :on-change #(om/set-state! owner :webhook-url (.. % -target -value))
                     :on-key-down #(when (= "Enter" (.-key %))
                                     (.focus (om/get-node owner "submit-button"))
                                     (submit-fn))}]
            [:label {:data-label "Webhook"
                     :data-placeholder "What's the webhook url?"}]]
           [:div.menu-buttons.make
            [:input.menu-button {:type "submit"
                                 :ref "submit-button"
                                 :tabIndex 1
                                 :value (if submitting?
                                          "Saving..."
                                          "Save webhook.")
                                 :on-click #(do (submit-fn)
                                              (utils/stop-event %))}]]]
          (when error
            [:div.slack-form-error error])])))))

(defn delete-slack-hook [{:keys [slack-hook team-uuid doc-id]} owner]
  (reify
    om/IDisplayName (display-name [_] "Delete slack hook")
    om/IInitState (init-state [_] {:submitting? nil})
    om/IRenderState
    (render-state [_ {:keys [submitting? submitted? error]}]
      (html
       [:button.menu-button {:class (when (or submitting? submitted?) "disabled")
                 :title "Remove channel from list."
                 :role "button"
                 :key (:db/id slack-hook)
                 :on-click (fn [e]
                             (om/set-state! owner :submitting? true)
                             (go (let [resp (async/<! (sente/ch-send-msg (om/get-shared owner :sente)
                                                                         [:team/delete-slack-integration
                                                                          {:slack-hook-id (:db/id slack-hook)
                                                                           :doc-id doc-id
                                                                           :team/uuid team-uuid}]
                                                                         30000
                                                                         (async/promise-chan)))]
                                   (if-not (taoensso.sente/cb-success? resp)
                                     (om/update-state! owner (fn [s] (assoc s
                                                                       :submitting? false
                                                                       :error "The request timed out, please try again.")))
                                     (do
                                       (om/update-state! owner #(assoc % :submitting? false :error nil))
                                       (if (= :success (:status resp))
                                         (do
                                           (om/set-state! owner :submitted? true))
                                         (om/set-state! owner :error (:error-msg resp))))))))}
        (cond submitting? "Deleting..."
              submitted? "Deleted"
              error error
              :else
              "Yes")]))))

(defn post-to-slack-button [{:keys [slack-hook team-uuid doc-id]} owner]
  (reify
    om/IDisplayName (display-name [_] "Slack button")
    om/IInitState (init-state [_] {:submitting? nil
                                   :comment-height 64})
    om/IRenderState
    (render-state [_ {:keys [submitting? submitted? error comment-height message]}]
      (html
       [:div.slack-channel
        [:a.vein.make {:role "button"
                       :on-click (if (om/get-state owner :show-options?)
                                   #(om/set-state! owner :show-options? false)
                                   #(om/set-state! owner :show-options? true))}
         (common/icon :slack)
         (let [ch (:slack-hook/channel-name slack-hook)]
           (if (= \# (first ch))
             (subs ch 1)
             ch))]
        (when (om/get-state owner :show-options?)
          (if (om/get-state owner :you-sure?)

            [:div.content
             [:h4.make "Sure you want to remove this channel?"]
             [:div.menu-buttons.make
              (om/build delete-slack-hook {:slack-hook slack-hook
                                           :doc-id doc-id
                                           :team-uuid team-uuid})
              [:button.menu-button {:on-click #(om/set-state! owner :you-sure? false)}
               "No"]]]

            [:div.content.make
             [:div.adaptive
              [:textarea {:style {:height comment-height}
                          :required "true"
                          :value (or message "")
                          :on-change #(do (om/set-state! owner :message (.. % -target -value))
                                          (let [node (.-target %)]
                                            (when (not= (.-scrollHeight node) (.-clientHeight node))
                                              (om/set-state! owner :comment-height (max 64 (.-scrollHeight node))))))}]
              [:label {:data-placeholder "Add a comment (optional)"
                       :data-label "Comment"}]]
             [:div.menu-buttons
              [:button.menu-button {:class (when (or submitting? submitted?) "disabled")
                                    :disabled (or submitting? submitted?)
                                    :key (:db/id slack-hook)
                                    :on-click (fn [e]
                                                (om/set-state! owner :submitting? true)
                                                (go (let [resp (async/<! (sente/ch-send-msg (om/get-shared owner :sente)
                                                                                            [:team/post-doc-to-slack
                                                                                             {:slack-hook-id (:db/id slack-hook)
                                                                                              :doc-id doc-id
                                                                                              :team/uuid team-uuid
                                                                                              :message message}]
                                                                                            30000
                                                                                            (async/promise-chan)))]
                                                      (if-not (taoensso.sente/cb-success? resp)
                                                        (om/update-state! owner (fn [s] (assoc s
                                                                                               :submitting? false
                                                                                               :error "Request timed out, please try again.")))
                                                        (do
                                                          (om/update-state! owner #(assoc % :submitting? false :error nil))
                                                          (if (= :success (:status resp))
                                                            (do
                                                              (om/update-state! owner #(assoc %
                                                                                              :error nil
                                                                                              :submitted? true
                                                                                              :submitting? false
                                                                                              :message nil
                                                                                              :comment-height 64))
                                                              (js/setTimeout #(when (om/mounted? owner) (om/set-state! owner :submitted? nil))
                                                                             1000))
                                                            (om/set-state! owner :error (:error-msg resp))))))))}

               (cond submitting? "Sending..."
                     submitted? "Sent!"
                     :else "Post this doc.")]
              [:button.slack-channel-remove {:on-click #(om/set-state! owner :you-sure? true)}
               (common/icon :times)]]

             (when error
               [:div.slack-form-error
                error])]))]))))

(defn slack-hooks [app owner]
  (reify
    om/IDisplayName (display-name [_] "Slack hooks")
    om/IInitState (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (fdb/add-attribute-listener (om/get-shared owner :team-db)
                                  :slack-hook/channel-name
                                  (om/get-shared owner :listener-key)
                                  #(om/refresh! owner)))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-attribute-listener (om/get-shared owner :team-db)
                                     :slack-hook/channel-name
                                     (om/get-shared owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! team-db]} (om/get-shared owner)
            hooks (->> (d/datoms @team-db :aevt :slack-hook/channel-name)
                    (map :e)
                    (map #(d/entity @team-db %))
                    (sort-by :db/id))]
        (html
         [:div.slack-channels
          [:a.vein.make {:role "button"
                         :on-click #(om/update-state! owner (fn [s] (update-in s [:show-form?] not)))}
           (common/icon :plus)
           [:span "Add a Channel"]]
          (when (get (om/get-state owner) :show-form? (empty? hooks))
            (list
             [:div.content.make
              [:p "Go to "
               [:a {:href "https://slack.com/services/new"
                    :target "_blank"
                    :role "button"}
                "Slack's integrations page"]
               ", then scroll to the end and add a new incoming webhook. "
               "Choose a channel, create the hook, then copy the webhook url."]]
             (om/build slack-form app)))
          (for [slack-hook hooks]
            (om/build post-to-slack-button {:doc-id (:document/id app)
                                            :team-uuid (get-in app [:team :team/uuid])
                                            :slack-hook slack-hook}
                      {:react-key (:db/id slack-hook)}))])))))


(defn slack [app owner]
  (reify
    om/IDisplayName (display-name [_] "Slack integration")
    om/IInitState (init-state [_] {:channel-name nil :webhook-url nil})
    om/IRender
    (render [_]
      (html
       [:section.menu-view.post-to-slack
        (om/build slack-hooks app)]))))
