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
         [:div.content
          [:p "Add a new Slack channel."]
          [:p "Go to the "
           [:a {:href "https://slack.com/services/new"
                :target "_blank"
                :role "button"}
            "Slack integrations page"]
           ", scroll to the bottom and add a new Incoming WebHook. Choose a channel, then copy the Webhook URL and fill out the form."]
          [:form.menu-invite-form.make {:on-submit submit-fn}
           [:input {:type "text"
                    :required "true"
                    :data-adaptive ""
                    :tabIndex 1
                    :value (or channel-name "")
                    :on-change #(om/set-state! owner :channel-name (.. % -target -value))
                    :on-key-down #(when (= "Enter" (.-key %))
                                    (.focus (om/get-node owner "webhook-input")))}]
           [:label {:data-placeholder "Channel"
                    :data-placeholder-nil "What channel are we posting to?"}]
           [:input {:ref "webhook-input"
                    :type "text"
                    :required "true"
                    :data-adaptive ""
                    :tabIndex 1
                    :value (or webhook-url "")
                    :on-change #(om/set-state! owner :webhook-url (.. % -target -value))
                    :on-key-down #(when (= "Enter" (.-key %))
                                    (.focus (om/get-node owner "submit-button"))
                                    (submit-fn))}]
           [:label {:data-placeholder "Webhook url"
                    :data-placeholder-nil "What's the webhook url"}]
           [:div.content.make
            [:div.menu-buttons
             [:a.menu-button {:role "button"
                              :ref "submit-button"
                              :tabIndex 1
                              :on-click #(do (submit-fn)
                                             (utils/stop-event %))}
              (if submitting?
                "Saving..."
                "Save webhook.")]]]]
          (when error
            [:p.make error])])))))

(defn post-to-slack-button [{:keys [slack-hook team-uuid doc-id]} owner]
  (reify
    om/IDisplayName (display-name [_] "Slack button")
    om/IInitState (init-state [_] {:submitting? nil})
    om/IRenderState
    (render-state [_ {:keys [submitting? submitted? error]}]
      (html
       [:a.menu-button {:class (when (or submitting? submitted?) "disabled")
                        :role "button"
                        :key (:db/id slack-hook)
                        :on-click (fn [e]
                                    (om/set-state! owner :submitting? true)
                                    (go (let [resp (async/<! (sente/ch-send-msg (om/get-shared owner :sente)
                                                                                [:team/post-doc-to-slack
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
                                                  (om/set-state! owner :submitted? true)
                                                  (js/setTimeout #(when (om/mounted? owner) (om/set-state! owner :submitted? nil))
                                                                 1000))
                                                (om/set-state! owner :error (:error-msg resp))))))))}
        (cond submitting? "Sending..."
              submitted? "Sent!"
              error error
              :else
              (str "Post this doc to "
                   (when-not (= \# (first (:slack-hook/channel-name slack-hook)))
                     "#")
                   (:slack-hook/channel-name slack-hook)))]))))

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
      (let [{:keys [cast! team-db]} (om/get-shared owner)]
        (html
         [:div.content
          [:div.menu-buttons
           (for [slack-hook (->> (d/datoms @team-db :aevt :slack-hook/channel-name)
                              (map :e)
                              (map #(d/entity @team-db %))
                              (sort-by :db/id))]
             (om/build post-to-slack-button {:doc-id (:document/id app)
                                             :team-uuid (get-in app [:team :team/uuid])
                                             :slack-hook slack-hook}
                       {:react-key (:db/id slack-hook)}))]])))))


(defn slack [app owner]
  (reify
    om/IDisplayName (display-name [_] "Slack integration")
    om/IInitState (init-state [_] {:channel-name nil :webhook-url nil})
    om/IRender
    (render [_]
      (html
       [:section.menu-view
        (om/build slack-form app)
        (om/build slack-hooks app)]))))
