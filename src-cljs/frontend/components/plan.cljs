(ns frontend.components.plan
  (:require [cljs-time.format :as time-format]
            [clojure.string :as str]
            [datascript :as d]
            [frontend.components.common :as common]
            [frontend.components.inspector :as inspector]
            [frontend.datascript :as ds]
            [frontend.datetime :as datetime]
            [frontend.db :as fdb]
            [frontend.models.plan :as plan-model]
            [frontend.models.team :as team-model]
            [frontend.sente :as sente]
            [frontend.utils :as utils]
            [goog.dom]
            [goog.dom.Range]
            [om.core :as om]
            [om.dom :as dom]
            [taoensso.sente])
  (:require-macros [sablono.core :refer (html)])
  (:import [goog.ui IdGenerator]))

(defn time-left [plan]
  (datetime/time-ago (- (:plan/trial-end plan)
                        (.getTime (js/Date.)))))

(defn trial-info [{:keys [plan]} owner]
  (reify
    om/IRender
    (render [_]
      (let [{:keys [cast! team-db]} (om/get-shared owner)]
        (html
         [:div.make
          [:p.make {:title (:plan/trial-end plan)}
           (if (plan-model/in-trial? plan)
             (str "This plan is still in trial for " (time-left plan))
             "The trial is over")]
          [:p.make
           [:a {:role "button"
                :on-click #(cast! :start-plan-clicked)}
            "Pay"]]])))))

(defn billing-email [{:keys [plan]} owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            submit-fn #(do (when-not (str/blank? (om/get-state owner :new-email))
                             (d/transact! (om/get-shared owner :team-db)
                                          [[:db/add (:db/id plan) :plan/billing-email (om/get-state owner :new-email)]]))
                           (om/set-state! owner :editing-email? false)
                           (om/set-state! owner :new-email ""))]
        (html
         [:div
          [:div.billing-email {:ref "billing-email"
                               :content-editable (if (om/get-state owner :editing-email?) true false)
                               :spell-check false
                               :on-key-down #(do
                                               (when (= "Enter" (.-key %))
                                                 (.preventDefault %)
                                                 (submit-fn)
                                                 (utils/stop-event %))
                                               (when (= "Escape" (.-key %))
                                                 (om/set-state! owner :editing-email? false)
                                                 (om/set-state! owner :new-email "")
                                                 (utils/stop-event %)))
                               :on-blur #(do (submit-fn)
                                             (utils/stop-event %))
                               :on-input #(om/set-state-nr! owner :new-email (goog.dom/getRawTextContent (.-target %)))}
           (:plan/billing-email plan)]
          [:a {:on-click #(do
                            (om/set-state! owner :editing-email? true)
                            (.focus (om/get-node owner "billing-email"))
                            (.select (goog.dom.Range/createFromNodeContents (om/get-node owner "billing-email")))
                            (.stopPropagation %))
               :role "button"
               :title "Change your billing email."}
           (common/icon :pencil)]])))))

(defn active-users [{:keys [plan]} owner]
  (reify
    om/IRender
    (render [_]
      (let [{:keys [cast! team-db]} (om/get-shared owner)
            active-custs (:plan/active-custs plan)]
        (html
         [:div
          "Over the last 30 days, " (count active-custs)
          (if (= 1 (count active-custs))
            " user on your team has been active."
            " users on your team have been active.")
          " This would cost you $" (max 10 (* (count active-custs) 10)) "/month."
          (for [cust active-custs]
            [:div cust])])))))

(defn paid-info [{:keys [plan team-uuid]} owner]
  (reify
    om/IRender
    (render [_]
      (let [{:keys [cast! team-db]} (om/get-shared owner)]
        (html
         [:div.make
          [:h4 "Credit card"]
          [:p
           (for [[k v] (filter #(= "credit-card" (namespace (first %))) plan)
                 :let [v (str v)]]
             [:tr.make
              [:td [:div {:title k} (str k)]]
              [:td [:div.connection-result {:title v}
                    v]]])]
          [:a {:on-click #(cast! :change-card-clicked)
               :role "button"}
           "Change card"]
          [:h4 "Billing email"]
          [:p "We'll send invoices to this email."]
          (om/build billing-email {:plan plan})
          [:h4 "Usage"]
          (when (plan-model/in-trial? plan)
            [:p
             "You still have "
             [:span {:title (:plan/trial-end plan)}
              (time-left plan)]
             " left in your trial. We won't start charging until your trial is over."])
          [:p "You pay $10/month for every active user on your team. Add users from the "
           [:a {:on-click #(cast! :team-settings-opened)
                :role "button"}
            "team permissions"]
           " page."]
          (om/build active-users {:plan plan})])))))

(defn plan-info [{:keys [plan-id team-uuid]} owner]
  (reify
    om/IInitState
    (init-state [_] {:watch-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (fdb/add-entity-listener (om/get-shared owner :team-db)
                               plan-id
                               (om/get-state owner :listener-key)
                               (fn [tx-report]
                                 (om/refresh! owner))))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-entity-listener (om/get-shared owner :team-db)
                                  plan-id
                                  (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! team-db]} (om/get-shared owner)
            plan (d/entity @team-db plan-id)]
        (html
         (if (:plan/trial-end plan) ;; wait for plan to load
           (if (:plan/paid? plan)
             (om/build paid-info {:plan (ds/touch+ plan)
                                  :team-uuid team-uuid})
             (om/build trial-info {:plan (ds/touch+ plan)
                                   :team-uuid team-uuid}))
           [:div.loading]))))))

(defn plan-overlay [app owner]
  (reify
    om/IInitState
    (init-state [_] {:watch-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (fdb/add-attribute-listener (om/get-shared owner :team-db)
                                  :plan/paid?
                                  (om/get-state owner :listener-key)
                                  (fn [tx-report]
                                    (om/refresh! owner)))
      (fdb/add-attribute-listener (om/get-shared owner :team-db)
                                  :team/plan
                                  (om/get-state owner :listener-key)
                                  (fn [tx-report]
                                    (om/refresh! owner))))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-attribute-listener (om/get-shared owner :team-db)
                                     :plan/paid?
                                     (om/get-state owner :listener-key))
      (fdb/remove-attribute-listener (om/get-shared owner :team-db)
                                     :team/plan
                                     (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! team-db]} (om/get-shared owner)
            team (team-model/find-by-uuid @team-db (get-in app [:team :team/uuid]))]
        (html
         [:div.menu-view
          [:div.content
           (when (:team/plan team)
             (om/build plan-info {:plan-id (:db/id (:team/plan team))
                                  :team-uuid (:team/uuid team)}))]])))))
