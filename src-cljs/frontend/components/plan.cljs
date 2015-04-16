(ns frontend.components.plan
  (:require [cljs-time.format :as time-format]
            [datascript :as d]
            [frontend.components.inspector :as inspector]
            [frontend.datascript :as ds]
            [frontend.db :as fdb]
            [frontend.models.team :as team-model]
            [frontend.utils :as utils]
            [om.core :as om]
            [om.dom :as dom])
  (:require-macros [sablono.core :refer (html)])
  (:import [goog.ui IdGenerator]))

(defn plan-info [plan-id owner]
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
         [:div.make
          [:h4.make "Credit card"]
          [:p.make
           (for [[k v] (filter #(= "credit-card" (namespace (first %))) plan)
                 :let [v (str v)]]
             [:tr.make
              [:td [:div {:title k} (str k)]]
              [:td [:div.connection-result {:title v}
                    v]]])]
          [:a {:on-click #(cast! :change-card-clicked)
               :role "button"}
           "Change card"]])))))

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
                                    (om/refresh! owner))))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-attribute-listener (om/get-shared owner :team-db)
                                     :plan/paid?
                                     (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! team-db]} (om/get-shared owner)
            team (team-model/find-by-uuid @team-db (get-in app [:team :team/uuid]))]
        (html
         [:div.menu-view
          [:div.content
           [:h3.make "Plan Settings"]
           [:p.make
            (if (:plan/paid? (:team/plan team))
              (om/build plan-info (:db/id (:team/plan team)))
              [:a {:role "button"
                   :on-click #(cast! :stripe-form-opened)}
               "Pay"])]]])))))
