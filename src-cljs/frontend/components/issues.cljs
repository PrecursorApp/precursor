(ns frontend.components.issues
  (:require [cljs-time.format :as time-format]
            [clojure.string :as str]
            [datascript :as d]
            [frontend.components.common :as common]
            [frontend.datascript :as ds]
            [frontend.datetime :as datetime]
            [frontend.db :as fdb]
            [frontend.sente :as sente]
            [frontend.urls :as urls]
            [frontend.utils :as utils]
            [goog.string :as gstring]
            [goog.string.format]
            [om.core :as om]
            [om.dom :as dom])
  (:require-macros [sablono.core :refer (html)])
  (:import [goog.ui IdGenerator]))

(defn issue-form [_ owner {:keys [issue-db]}]
  (reify
    om/IInitState (init-state [_] {:issue-title ""})
    om/IRenderState
    (render-state [_ {:keys [issue-title]}]
      (html
        [:form.menu-invite-form.make {:on-submit #(do (utils/stop-event %)
                                                    (when (seq issue-title)
                                                      (d/transact! issue-db [{:db/id -1
                                                                              :issue/title issue-title
                                                                              :frontend/issue-id (utils/squuid)}])
                                                      (om/set-state! owner :issue-title "")))}
         [:input {:type "text"
                  :value issue-title
                  :required "true"
                  :data-adaptive ""
                  :onChange #(om/set-state! owner :issue-title (.. % -target -value))}]
         [:label {:data-placeholder (str "Sounds good" "; 100 characters left")
                  :data-placeholder-nil "How can we improve Precursor?"
                  :data-placeholder-busy "Your idea in less than 140 characters?"}]]))))

;; XXX: handle logged-out users
(defn vote-box [{:keys [issue]} owner]
  (reify
    om/IRender
    (render [_]
      (let [{:keys [cast! issue-db cust]} (om/get-shared owner)
            voted? (d/q '[:find ?e .
                          :in $ ?issue-id ?email
                          :where
                          [?issue-id :issue/votes ?e]
                          [?e :vote/cust ?email]]
                        @issue-db (:db/id issue) (:cust/email cust))]
        (html

          (if voted?
            [:div.voted]

            [:a.issue-vote {:role "button"
                            :on-click #(d/transact! issue-db
                                                    [{:db/id (:db/id issue)
                                                      :issue/votes {:db/id -1
                                                                    :frontend/issue-id (utils/squuid)
                                                                    :vote/cust (:cust/email cust)}}])}
             [:span.issue-vote-count (count (:issue/votes issue))]
             (common/icon :north)]))))))

(defn issue [{:keys [issue-id]} owner]
  (reify
    om/IInitState
    (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))
                     :title nil
                     :description nil})
    om/IDidMount
    (did-mount [_]
      (fdb/add-entity-listener (om/get-shared owner :issue-db)
                               issue-id
                               (om/get-state owner :listener-key)
                               (fn [tx-report]
                                 ;; This should ask the user if he wants to reload
                                 (om/refresh! owner))))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-entity-listener (om/get-shared owner :issue-db)
                                  issue-id
                                  (om/get-state owner :listener-key)))
    om/IRenderState
    (render-state [_ {:keys [title description]}]
      (let [{:keys [cast! issue-db]} (om/get-shared owner)
            issue (ds/touch+ (d/entity @issue-db issue-id))]
        (html
         [:div.content.make
          (om/build vote-box {:issue issue})
          [:p "Title "
           [:input {:value (or title (:issue/title issue ""))
                    :on-change #(om/set-state! owner :title (.. % -target -value))}]]
          [:p "Description "
           [:input {:value (or description (:issue/description issue ""))
                    :on-change #(om/set-state! owner :description (.. % -target -value))}]]
          [:p
           [:a {:role "button"
                :on-click #(d/transact! issue-db [(utils/remove-map-nils
                                                   {:db/id issue-id
                                                    :issue/title title
                                                    :issue/description description})])}
            "Save"]
           " "
           [:a {:on-click #(d/transact! issue-db [[:db.fn/retractEntity issue-id]])
                :role "button"}
            "Delete"]]])))))

(defn issue-summary [{:keys [issue-id]} owner]
  (reify
    om/IInitState
    (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (fdb/add-entity-listener (om/get-shared owner :issue-db)
                               issue-id
                               (om/get-state owner :listener-key)
                               (fn [tx-report]
                                 (om/refresh! owner))))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-entity-listener (om/get-shared owner :issue-db)
                                  issue-id
                                  (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! issue-db]} (om/get-shared owner)
            issue (ds/touch+ (d/entity @issue-db issue-id))]
        (html
         [:div.issue
          (om/build vote-box {:issue issue})
          [:div.issue-info {:on-click #(cast! :issue-expanded {:issue-id issue-id})
                 :style {:cursor "pointer"
                         :display "inline-block"}}
           (:issue/title issue)]])))))

(defn issues [app owner {:keys [submenu]}]
  (reify
    om/IInitState
    (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (fdb/setup-issue-listener!
       (om/get-shared owner :issue-db)
       (om/get-state owner :listener-key)
       (om/get-shared owner :comms)
       (om/get-shared owner :sente))
      (fdb/add-attribute-listener (om/get-shared owner :issue-db)
                                  :frontend/issue-id
                                  (om/get-state owner :listener-key)
                                  (fn [tx-report]
                                    (om/refresh! owner)))
      (sente/subscribe-to-issues (:sente app)
                                 (om/get-shared owner :comms)
                                 (om/get-shared owner :issue-db)))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :issue-db) (om/get-state owner :listener-key))
      (fdb/remove-attribute-listener (om/get-shared owner :issue-db)
                                     :frontend/issue-id
                                     (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! issue-db]} (om/get-shared owner)
            issue-ids (map :e (d/datoms @issue-db :aevt :issue/title))]
        (html
         [:div.menu-view
          [:div.content
           (when-not submenu
             [:div.make {:key "issue-form"}
              (om/build issue-form {} {:opts {:issue-db issue-db}})])
           [:div.make {:key (or submenu "summary")}
            (if submenu
              (om/build issue {:issue-id (:active-issue-id app)} {:key :issue-id})
              (when (seq issue-ids)
                (om/build-all issue-summary (map (fn [i] {:issue-id i}) (sort issue-ids))
                              {:key :issue-id
                               :opts {:issue-db issue-db}})))]]])))))
