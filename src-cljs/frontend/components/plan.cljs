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
            [frontend.utils :as utils]
            [goog.dom]
            [goog.dom.Range]
            [om.core :as om]
            [om.dom :as dom])
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
          [:div {:ref "billing-email"
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

(defn paid-info [{:keys [plan]} owner]
  (reify
    om/IRender
    (render [_]
      (let [{:keys [cast! team-db]} (om/get-shared owner)]
        (html
         [:div.make
          [:h4.make "Credit card"]
          [:p.make
           (for [[k v] (filter #(= "credit-card" (namespace (first %))) plan)
                 :let [v (str v)]]
             [:tr.make
              [:td [:div {:title k} (str k)]]
              [:td [:div.connection-result {:title v}
                    v]]])]
          [:a.make {:on-click #(cast! :change-card-clicked)
                    :role "button"}
           "Change card"]
          (om/build billing-email {:plan plan})
          [:h4.make "Usage"]
          (when (plan-model/in-trial? plan)
            [:p.make
             "You still have "
             [:span {:title (:plan/trial-end plan)}
              (time-left plan)]
             " left in your trial. We won't start charging until your trial is over."])
          [:p.make "You pay $10/month for every active user on your team. Add users from the "
           [:a.make {:on-click #(cast! :team-settings-opened)
                     :role "button"}
            "team permissions"]
           " page."]])))))

(defn plan-info [{:keys [plan-id]} owner]
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
         (if (:plan/trial-end plan)
           (if (:plan/paid? plan)
             (om/build paid-info {:plan (ds/touch+ plan)})
             (om/build trial-info {:plan (ds/touch+ plan)}))
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
           [:p.make
            (when (:team/plan team)
              (om/build plan-info {:plan-id (:db/id (:team/plan team))}))]]])))))
