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
            [frontend.urls :as urls]
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
           [:div.content {:key "history"}
            (for [{:keys [cust instant added?]} (reverse history)]
              [:div.access-card.make {:key (str instant cust)}
               [:div.access-avatar
                [:img.access-avatar-img
                 {:src (utils/gravatar-url cust)}]]
               [:div.access-details
                [:span {:title cust}
                 cust]
                [:span.access-status
                 (str "Was marked as" (if added? " active " " inactive ") (format-access-date instant) ".")]]])]))))))

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

           ; [:div.content.make
           ;  (om/build active-users {:plan plan}
           ;            {:react-key "active-users"})]

           (om/build active-history {:team-uuid team-uuid}
                     {:react-key "active-history"})])))))

(defn invoice-component [{:keys [invoice-id team-uuid]} owner]
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


          ; [:div.invoice {:style {:margin-bottom "1em"}}
          ;  [:div.invoice-number "Invoice #" (:db/id invoice)]
          ;  [:div.invoice-date (datetime/medium-consistent-date (:invoice/date invoice))]
          ;  [:div.invoice-description (:invoice/description invoice)]
          ;  [:div.invoice-discount
          ;   (when (= :coupon/product-hunt (:discount/coupon invoice))
          ;     "Discount: 50% off for first 6 months")]
          ;  [:div.invoice-total
          ;   "Total: " (format-stripe-cents (:invoice/total invoice))]
          ;  [:div.invoice-status
          ;   "Status: "
          ;   (cond (:invoice/paid? invoice)
          ;         "paid"

          ;         (:invoice/next-payment-attempt invoice)
          ;         (str "will charge on " (datetime/medium-consistent-date (:invoice/next-payment-attempt invoice)))
          ;         :else "unpaid")]]

          [:tr.invoice.make
           [:td.invoice-date
            (datetime/medium-consistent-date (:invoice/date invoice))]
           [:td.invoice-id
            [:a {:href (urls/invoice-url team-uuid invoice-id)
                 :target "_blank"}
             (str "#" (:db/id invoice))]]
           [:td.invoice-total
            (format-stripe-cents (:invoice/total invoice))]
           [:td.invoice-status
            (cond (:invoice/paid? invoice)
                  "Paid"

                  (:invoice/next-payment-attempt invoice)
                  (str "Charging " (datetime/medium-consistent-date (:invoice/next-payment-attempt invoice)))

                  :else "Unpaid")]]




          )))))

(defn invoices [{:keys [plan team-uuid]} owner]
  (reify
    om/IRender
    (render [_]
      (let [sorted-invoices (->> plan
                              :plan/invoices
                              ;; don't show $0 invoices
                              (filter #(not (zero? (:invoice/total %))))
                              (sort-by :invoice/date)
                              reverse)]
        (html
          [:div.menu-view
           [:table.invoices-table
            [:thead.invoices-head
             [:tr.make
              [:th.invoice-date "date"]
              [:th.invoice-id "invoice"]
              [:th.invoice-total "total"]
              [:th.invoice-status "status"]]]
            [:tbody.invoices-body
             (for [invoice sorted-invoices]
               (om/build invoice-component {:invoice-id (:db/id invoice)
                                            :team-uuid team-uuid}
                         {:key :invoice-id}))]]])))))

(defn payment [{:keys [plan team-uuid]} owner]
  (reify
    om/IRender
    (render [_]
      (let [{:keys [cast! team-db]} (om/get-shared owner)]
        (html
         [:div.menu-view.credit-card
          [:div.content.make
           [:div.disabled-input {:data-after "Card number"}
            (str "✳✳✳✳ ✳✳✳✳ ✳✳✳✳ " (:credit-card/last4 plan))]]
          [:div.content.make
           [:div.disabled-input {:data-after "Expiration"}
            (str (:credit-card/exp-month plan) "/" (:credit-card/exp-year plan))]]
          [:div.calls-to-action.content.make
           [:a.bubble-button {:role "button"
                              :on-click #(cast! :change-card-clicked)}
            "Change card."]]])))))

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



           ; [:div.billing-email {:ref "billing-email"
           ;                      :content-editable (if (om/get-state owner :editing-email?) true false)
           ;                      :spell-check false
           ;                      :on-key-down #(do
           ;                                      (when (= "Enter" (.-key %))
           ;                                        (.preventDefault %)
           ;                                        (submit-fn)
           ;                                        (utils/stop-event %))
           ;                                      (when (= "Escape" (.-key %))
           ;                                        (om/set-state! owner :editing-email? false)
           ;                                        (om/set-state! owner :new-email "")
           ;                                        (utils/stop-event %)))
           ;                      :on-blur #(do (submit-fn)
           ;                                    (utils/stop-event %))
           ;                      :on-input #(om/set-state-nr! owner :new-email (goog.dom/getRawTextContent (.-target %)))}
           ;  (:plan/billing-email plan)]
           ; [:a {:on-click #(do
           ;                   (om/set-state! owner :editing-email? true)
           ;                   (.focus (om/get-node owner "billing-email"))
           ;                   (.select (goog.dom.Range/createFromNodeContents (om/get-node owner "billing-email")))
           ;                   (.stopPropagation %))
           ;      :role "button"
           ;      :title "Change your billing email."}
           ;  (common/icon :pencil)]

           [:form.menu-invite-form
            [:input {:type "text"
                     :required "true"
                     :data-adaptive ""
                     :value (or (:plan/billing-email plan) "")
                     :on-change #(do
                                   (.preventDefault %)
                                   (submit-fn)
                                   (utils/stop-event %))}]
            [:label {:data-placeholder "We'll send your invoices here"
                     :data-placeholder-nil "We need an email to send invoices"}]]]
          [:div.calls-to-action.content.make
           [:a.bubble-button {:role "button"
                              :on-click #(do (submit-fn)
                                           (utils/stop-event %))}
            "Save information."]]])))))

(defn paid-summary [{:keys [plan team-uuid]} owner]
  (reify
    om/IRender
    (render [_]
      (html
       [:div
        (if (plan-model/in-trial? plan)
          [:div.content.make
           [:h4
            "Your team plan starts " (datetime/month-day (:plan/next-period-start plan))
            " for " (format-stripe-cents (plan-model/cost plan)) "."]]
          [:div.content.make
           [:h4
            "Your team plan renews " (datetime/month-day (:plan/next-period-start plan))
            " for " (format-stripe-cents (plan-model/cost plan)) "."]])
        [:div.content.make
         [:p
          (case (count (:plan/active-custs plan))
            0 "No users are active on your team, yet."
            1 "One user is active on your team."
            (str (count (:plan/active-custs plan)) " users are active on your team."))
          " Your plan renews monthly. "
          ;; discount
          (when-let [coupon (:discount/coupon plan)]
            (str "The " (:coupon/percent-off coupon) "% " (:coupon/stripe-id coupon)
                 " discount is included in the renewal price. "))
          ;; trial days
          (when (plan-model/in-trial? plan)
            (str "The " (time-left plan) " left in your trial are included in the start date."))]]]))))

(defn trial-summary [{:keys [plan team-uuid]} owner]
  (reify
    om/IRender
    (render [_]
      (html
       [:div
        (if (plan-model/in-trial? plan)
          [:div.content.make
           [:h4
            "Your have " (time-left plan) " left in your trial."]]
          [:div.content.make
           [:h4
            "Your free trial is over."]])
        [:div.content.make
         [:p
          (case (count (:plan/active-custs plan))
            0 "No users are active on your team, yet."
            1 "One user is active on your team."
            (str (count (:plan/active-custs plan)) " users are active on your team."))
          " Your plan will cost " (format-stripe-cents (plan-model/cost plan)) "/month. "
          ;; discount
          (when-let [coupon (:discount/coupon plan)]
            (str "The " (:coupon/percent-off coupon) "% " (:coupon/stripe-id coupon)
                 " discount is included in the cost. "))
          ;; trial days
          (if (plan-model/in-trial? plan)
            "We won't start charging until your trial ends."
            "Add payment below to keep using Precursor with your team.")]]]))))

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
          (if (:plan/paid? plan)
            (om/build paid-summary {:plan plan :team-uuid team-uuid} {:react-key "paid-summary"})
            (om/build trial-summary {:plan plan :team-uuid team-uuid} {:react-key "trial-summary"}))
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
               [:div.content.make.store-credit
                [:span (str "You have " (format-stripe-cents (Math/abs (:plan/account-balance plan))) " of credit for future payments.")]])))])))))

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
