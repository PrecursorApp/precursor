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
            [goog.string :as gstring]
            [goog.string.format]
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
          (when (= "product-hunt" (:plan/coupon-code plan))
            "You have the Product Hunt discount, which gives you 50% off for the first 6 months.")
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


(defn active-history [{:keys [team-uuid]} owner]
  (reify
    om/IInitState (init-state [_] {:history nil})
    om/IDidMount
    (did-mount [_]
      ;; We're not using the controls here because the history state gets stale fast
      ;; May turn out to be a bad idea
      ;; Ideally, we'd have the history in our frontend db
      (sente/send-msg (om/get-shared owner :sente) [:team/plan-active-history {:team/uuid team-uuid}]
                      20000
                      (fn [res]
                        (if (taoensso.sente/cb-success? res)
                          (om/set-state! owner :history (:history res))
                          (comment "do something about errors")))))
    om/IRenderState
    (render-state [_ {:keys [history]}]
      (let [{:keys [cast! team-db]} (om/get-shared owner)]
        (html
         (if (nil? active-users)
           [:div.loading {:key "loading"} "Loading"]
           [:div {:key "history"}
            (for [{:keys [cust instant added?]} (reverse history)]
              [:div (str cust (if added? " was marked active at " " was marked inactive at ") instant)])]))))))

(defn format-stripe-cents [cents]
  (let [pennies (mod cents 100)
        dollars (/ (- cents pennies) 100)]
    (gstring/format "$%d.%02d" dollars pennies)))

(defn invoice-component [{:keys [invoice-id]} owner]
  (reify
    om/IInitState
    (init-state [_] {:watch-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (fdb/add-entity-listener (om/get-shared owner :team-db)
                               invoice-id
                               (om/get-state owner :listener-key)
                               (fn [tx-report]
                                 (om/refresh! owner))))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-entity-listener (om/get-shared owner :team-db)
                                  invoice-id
                                  (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [invoice (d/touch (d/entity @(om/get-shared owner :team-db) invoice-id))]
        (html
         [:div.invoice {:style {:margin-bottom "1em"}}
          [:div.invoice-number "Invoice #" (:db/id invoice)]
          [:div.invoice-date (datetime/medium-consistent-date (:invoice/date invoice))]
          [:div.invoice-description (:invoice/description invoice)]
          [:div.invoice-discount
           (when (= :coupon/product-hunt (:discount/coupon invoice))
             "Discount: 50% off for first 6 months")]
          [:div.invoice-total
           "Total: " (format-stripe-cents (:invoice/total invoice))]
          [:div.invoice-status
           "Status: "
           (cond (:invoice/paid? invoice)
                 "paid"

                 (:invoice/next-payment-attempt invoice)
                 (str "will charge on " (datetime/medium-consistent-date (:invoice/next-payment-attempt invoice)))
                 :else "unpaid")]])))))

(defn invoices [{:keys [plan team-uuid]} owner & {:keys [limit] :as opts}]
  (reify
    om/IRender
    (render [_]
      (let [sorted-invoices (reverse (sort-by :invoice/date (:plan/invoices plan)))]
        (html
         [:div
          (for [invoice (if limit
                          (take limit sorted-invoices)
                          sorted-invoices)]
            (om/build invoice-component {:invoice-id (:db/id invoice)} {:key :invoice-id}))])))))

(defn paid-info [{:keys [plan team-uuid]} owner]
  (reify
    om/IRender
    (render [_]
      (let [{:keys [cast! team-db]} (om/get-shared owner)]
        (html
         [:div.make
          [:h4 "Credit card"]
          (for [[k v] (filter #(= "credit-card" (namespace (first %))) plan)
                :let [v (str v)]]
            [:tr.make
             [:td [:div {:title k} (str k)]]
             [:td [:div.connection-result {:title v}
                   v]]])
          [:a {:on-click #(cast! :change-card-clicked)
               :role "button"}
           "Change card"]
          [:h4 "Billing email"]
          [:p "We'll send invoices to this email."]
          (om/build billing-email {:plan plan}
                    {:react-key "billing-email"})
          [:h4 "Discount"]
          [:p "Coupon: " (name (:discount/coupon plan ""))]
          [:p (str "Start: " (:discount/start plan))]
          [:p (str "End: " (:discount/end plan))]
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
          (om/build active-users {:plan plan}
                    {:react-key "active-users"})
          [:h4 "Plan history"]
          (om/build active-history {:team-uuid team-uuid}
                    {:react-key "active-history"})
          [:h4 "Invoices"]
          (om/build invoices {:plan plan :team-uuid team-uuid}
                    {:react-key "invoices"})
          ])))))

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
                                  :team-uuid team-uuid}
                       {:react-key "paid-info"})
             (om/build trial-info {:plan (ds/touch+ plan)
                                   :team-uuid team-uuid}
                       {:react-key "trial-info"}))
           [:div.loading {:key "loading"}]))))))

(defn billing-start [app owner]
  (reify
    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)]
        (html
          [:div.menu-view
           [:div.divider.make]
           [:div.content.make
            [:h4 "Team of 3 @ $10/mo."]]
           [:div.content.make
            [:p "We'll begin charging in 14 days when your free trial expires. "
                "Then your Product Hunt discount will save you 50% for the next 6 months. "
                "Your first invoice will be $15. "]]
           [:div.divider.make]
           [:a.vein.make {:on-click         #(cast! :billing-info-opened)
                          :on-touch-end #(do (cast! :billing-info-opened) (.preventDefault %))}
            (common/icon :info)
            [:span "Information"]]
           [:a.vein.make {:on-click         #(cast! :billing-payment-opened)
                          :on-touch-end #(do (cast! :billing-payment-opened) (.preventDefault %))}
            (common/icon :credit)
            [:span "Payment"]]
           [:a.vein.make {:on-click         #(cast! :billing-invoices-opened)
                          :on-touch-end #(do (cast! :billing-invoices-opened) (.preventDefault %))}
            (common/icon :docs)
            [:span "Invoices"]]
           [:a.vein.make {:on-click         #(cast! :billing-activity-opened)
                          :on-touch-end #(do (cast! :billing-activity-opened) (.preventDefault %))}
            (common/icon :activity)
            [:span "Activity"]]
           [:a.vein.make {:on-click         #(cast! :billing-discount-opened)
                          :on-touch-end #(do (cast! :billing-discount-opened) (.preventDefault %))}
            (common/icon :heart)
            [:span "Discount"]]])))))

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
          (when (:team/plan team)
            ; (om/build plan-info {:plan-id (:db/id (:team/plan team))
            ;                      :team-uuid (:team/uuid team)}
            ;           {:react-key "plan-info"})

            (om/build billing-start app)


            ))))))
