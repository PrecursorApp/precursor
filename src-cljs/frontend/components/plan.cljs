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
            [frontend.utils.date :refer (date->bucket)]
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

(defn format-access-date [date]
  (date->bucket date :sentence? true))

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
              [:div.access-card.make {:key (str instant cust)}
               [:div.access-avatar
                [:img.access-avatar-img
                 {:src (utils/gravatar-url cust)}]]
               [:div.access-details
                [:span {:title cust}
                 cust]
                [:span.access-status
                 (str "Was marked " (if added? "active" "inactive") " " (format-access-date instant))]]])]))))))

(defn format-stripe-cents
  "Formats Stripe's currency values into ordinary dollar format
   500 -> $5
   489 -> $4.89"
  [cents]
  (let [abs-cents (Math/abs cents)
        pennies (mod abs-cents 100)
        dollars (/ (- abs-cents pennies) 100)]
    (if (pos? pennies)
      (gstring/format "$%s%d.%02d" (if (neg? cents) "-" "") dollars pennies)
      (gstring/format "$%s%d" (if (neg? cents) "-" "") dollars))))

(defn activity [{:keys [plan team-uuid]} owner]
  (reify
    om/IRender
    (render [_]
      (let [{:keys [cast! team-db]} (om/get-shared owner)]
        (html
         [:div.menu-view
          [:div.content.make
           [:h4 "Usage"]
           [:p "You pay $10/month for every active user on your team. Add users from the "
            [:a {:on-click #(cast! :team-settings-opened)
                 :role "button"}
             "team permissions"]]
           (om/build active-users {:plan plan}
                     {:react-key "active-users"})
           [:h4 "Plan history"]
           (om/build active-history {:team-uuid team-uuid}
                     {:react-key "active-history"})]])))))

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

(defn invoices [{:keys [plan team-uuid]} owner]
  (reify
    om/IRender
    (render [_]
      (let [sorted-invoices (reverse (sort-by :invoice/date (:plan/invoices plan)))]
        (html
         [:div.menu-view
          [:div.content.make
           (for [invoice sorted-invoices]
             (om/build invoice-component {:invoice-id (:db/id invoice)} {:key :invoice-id}))]])))))

(defn payment [{:keys [plan team-uuid]} owner]
  (reify
    om/IRender
    (render [_]
      (let [{:keys [cast! team-db]} (om/get-shared owner)]
        (html
         [:div.menu-view
          [:div.content.make
           [:div.make
            (for [[k v] (filter #(= "credit-card" (namespace (first %))) plan)
                  :let [v (str v)]]
              [:tr.make
               [:td [:div {:title k} (str k)]]
               [:td [:div.connection-result {:title v}
                     v]]])]]])))))

(defn info [{:keys [plan team-uuid]} owner]
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
         [:div.menu-view
          [:div.content.make
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
            (common/icon :pencil)]]])))))

(defn start [{:keys [plan team-uuid]} owner]
  (reify
    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)
            open-menu-props (fn [submenu]
                              {:on-click #(cast! :plan-submenu-opened {:submenu submenu})
                               :on-touch-end #(do (cast! :plan-submenu-opened {:submenu submenu})
                                                  (.preventDefault %))})]
        (html
         [:div.menu-view
          [:div.divider.make]
          [:div.content.make
           [:h4 "Your team plan renews June 7 for $15."]]
          [:div.content.make
           [:p "3 users are active on your team. "
            "Your plan renews monthly. "
            "Product Hunt's 50% discount is included in the renewal price. "
            "The 14 days left in your trial are included in the renewal date. "]]
          [:div.divider.make]
          (if-not (:plan/paid? plan)
            [:a.vein.make {:on-click #(cast! :start-plan-clicked)}
             (common/icon :credit)
             [:span "Add payment"]]
            (list
             [:a.vein.make (open-menu-props :info)
              (common/icon :info)
              [:span "Information"]]
             [:a.vein.make (open-menu-props :payment)
              (common/icon :credit)
              [:span "Payment"]]
             [:a.vein.make (open-menu-props :invoices)
              (common/icon :docs)
              [:span "Invoices"]]
             [:a.vein.make (open-menu-props :activity)
              (common/icon :activity)
              [:span "Activity"]]
             [:a.vein.make (open-menu-props :discount)
              (common/icon :heart)
              [:span "Discount"]]
             (when (neg? (:plan/account-balance plan))
               [:span "Credit " (format-stripe-cents (Math/abs (:plan/account-balance plan)))])))])))))

(def plan-components
  {:start start
   :info info
   :payment payment
   :invoices invoices
   :activity activity
   :discount info})

(defn plan-menu* [{:keys [plan-id team-uuid submenu]} owner]
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
            plan (ds/touch+ (d/entity @team-db plan-id))
            component-key (or submenu :start)
            component (get plan-components component-key)]
        (if (:plan/trial-end plan) ;; wait for plan to load
          (om/build component {:plan plan :team-uuid team-uuid} {:react-key component-key})
          (dom/div {:className "loading"}))))))

(defn plan-menu [app owner {:keys [submenu]}]
  (reify
    om/IInitState
    (init-state [_] {:watch-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (fdb/add-attribute-listener (om/get-shared owner :team-db)
                                  :team/plan
                                  (om/get-state owner :listener-key)
                                  (fn [tx-report]
                                    (om/refresh! owner))))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-attribute-listener (om/get-shared owner :team-db)
                                     :team/plan
                                     (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! team-db]} (om/get-shared owner)
            team (team-model/find-by-uuid @team-db (get-in app [:team :team/uuid]))]
        (html
         (when (:team/plan team)
           (om/build plan-menu* {:plan-id (:db/id (:team/plan team))
                                 :team-uuid (:team/uuid team)
                                 :submenu submenu}
                     {:react-key "plan-component*"})))))))
