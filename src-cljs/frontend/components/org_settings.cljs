(ns frontend.components.org-settings
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.set]
            [frontend.async :refer [put!]]
            [frontend.routes :as routes]
            [frontend.datetime :as datetime]
            [frontend.models.organization :as org-model]
            [frontend.models.plan :as plan-model]
            [frontend.models.repo :as repo-model]
            [frontend.models.user :as user-model]
            [frontend.components.common :as common]
            [frontend.components.forms :as forms]
            [frontend.components.inputs :as inputs]
            [frontend.components.plans :as plans-component]
            [frontend.components.shared :as shared]
            [frontend.state :as state]
            [frontend.stripe :as stripe]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.github :as gh-utils]
            [frontend.utils.state :as state-utils]
            [frontend.utils.vcs-url :as vcs-url]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [clojure.string :as string]
            [goog.string :as gstring]
            [goog.string.format]
            [goog.style])
  (:require-macros [cljs.core.async.macros :as am :refer [go go-loop alt!]]
                   [dommy.macros :refer [node sel sel1]]
                   [frontend.utils :refer [html]]))

(defn sidebar [{:keys [subpage plan org-name]} owner]
  (reify
    om/IRender
    (render [_]
      (letfn [(nav-links [templates]
                (map (fn [{:keys [page text]} msg]
                       [:li {:class (when (= page subpage) :active)}
                        [:a {:href (str "#" (name page))} text]])
                     templates))]
        (html [:div.span3
               [:ul.nav.nav-list.well
                [:li.nav-header "Organization settings"]
                [:li.divider]
                [:li.nav-header "Overview"]
                (nav-links [{:page :projects :text "Projects"}
                            {:page :users :text "Users"}])
                [:li.nav-header "Plan"]
                (when plan
                  (if (plan-model/can-edit-plan? plan org-name)
                    (nav-links [{:page :containers :text "Add containers"}
                                {:page :organizations :text "Organizations"}
                                {:page :billing :text "Billing info"}
                                {:page :cancel :text "Cancel"}])
                    (nav-links [{:page :plan :text "Choose plan"}])))]])))))

(defn non-admin-plan [{:keys [org-name login]} owner]
  (reify
    om/IRender
    (render [_]
      (html [:div.row-fluid.plans
             [:div.span12
              [:h3
               "Do you want to create a plan for an organization that you don't admin?"]
              [:ol
               [:li
                "Sign up for a plan from your "
                [:a {:href (routes/v1-org-settings-subpage {:org login
                                                            :subpage "plan"})}
                 "\"personal organization\" page"]]
               [:li
                "Add " org-name
                " to the list of organizations you pay for or transfer the plan to "
                org-name " from the "
                [:a {:href (routes/v1-org-settings-subpage {:org login
                                                            :subpage "organizations"})}
                 "plan's organization page"]
                "."]]]]))))

(defn users [app owner]
  (reify
    om/IRender
    (render [_]
      (let [users (get-in app state/org-users-path)
            projects (get-in app state/org-projects-path)
            org-name (get-in app state/org-name-path)
            projects-by-follower (org-model/projects-by-follower projects)
            sorted-users (sort-by (fn [u]
                                    (- (count (get projects-by-follower (:login u)))))
                                  users)]
        (html
         [:div.users
          [:h2
           "CircleCI users in the " org-name " organization"]
          [:div
           (if-not (seq users)
             [:h4 "No users found."])
           [:div
            (for [user sorted-users
                  :let [login (:login user)
                        followed-projects (get projects-by-follower login)]]
              [:div.well.om-org-user
               {:class (if (zero? (count followed-projects))
                         "fail"
                         "success")}

               [:img.gravatar {:src (gh-utils/make-avatar-url user :size 45)}]
               [:div.om-org-user-projects-container
                [:h4
                 (if (seq followed-projects)
                   (str login " is following:")
                   (str login " is not following any " org-name  " projects"))]
                [:div.om-org-user-projects
                 (for [project (sort-by (fn [p] (- (count (:followers p)))) followed-projects)
                       :let [vcs-url (:vcs_url project)]]
                   [:div.om-org-user-project
                    [:a {:href (routes/v1-project-dashboard {:org (vcs-url/org-name vcs-url)
                                                             :repo (vcs-url/repo-name vcs-url)})}
                     (vcs-url/project-name vcs-url)]])]]])]]])))))

(defn equalize-size
  "Given a node, will find all elements under node that satisfy selector and change
   the size of every element so that it is the same size as the largest element."
  [node selector]
  (let [items (sel node selector)
        sizes (map goog.style/getSize items)
        max-width (apply max (map #(.-width %) sizes))
        max-height (apply max (map #(.-height %) sizes))]
    (doseq [item items]
      (goog.style/setSize item max-width max-height))))

(defn followers-container [followers owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (equalize-size (om/get-node owner) ".follower-container"))
    om/IDidUpdate
    (did-update [_ _ _]
      (equalize-size (om/get-node owner) ".follower-container"))
    om/IRender
    (render [_]
      (html
       [:div.followers-container.row-fluid
        [:div.row-fluid
         (for [follower followers]
           [:span.follower-container
            {:style {:display "inline-block"}}
            [:img.gravatar
             {:src (gh-utils/make-avatar-url follower :size 30)}]
            " "
            [:span (:login follower)]])]]))))

(defn projects [app owner]
  (reify
    om/IRender
    (render [_]
      (let [users (get-in app state/org-users-path)
            projects (get-in app state/org-projects-path)
            {followed-projects true unfollowed-projects false} (group-by #(pos? (count (:followers %)))
                                                                         projects)
            org-name (get-in app state/org-name-path)]
        (html
         [:div
          [:div.followed-projects.row-fluid
           [:h2 "Followed projects"]
           (if-not (seq followed-projects)
             [:h4 "No followed projects found."]

             [:div.span8
              (for [project followed-projects
                    :let [vcs-url (:vcs_url project)]]
                [:div.row-fluid
                 [:div.span12.success.well
                  [:div.row-fluid
                   [:div.span12
                    [:h4
                     [:a {:href (routes/v1-project-dashboard {:org (vcs-url/org-name vcs-url)
                                                              :repo (vcs-url/repo-name vcs-url)})}
                      (vcs-url/project-name vcs-url)]
                     " "
                     [:a.edit-icon {:href (routes/v1-project-settings {:org (vcs-url/org-name vcs-url)
                                                                       :repo (vcs-url/repo-name vcs-url)})}
                      [:i.fa.fa-gear]]
                     " "
                     [:a.github-icon-link {:href vcs-url}
                      [:i.fa.fa-github]]]]]
                  (om/build followers-container (:followers project))]])])]
          [:div.row-fluid
           [:h2 "Untested repos"]
           (if-not (seq unfollowed-projects)
             [:h4 "No untested repos found."]

             [:div.span8
              (for [project unfollowed-projects
                    :let [vcs-url (:vcs_url project)]]
                [:div.row-fluid
                 [:div.fail.span12.well
                  [:h4
                   [:a {:href (routes/v1-project-dashboard {:org (vcs-url/org-name vcs-url)
                                                            :repo (vcs-url/repo-name vcs-url)})}
                    (vcs-url/project-name vcs-url)]
                   " "
                   [:a.edit-icon {:href (routes/v1-project-settings {:org (vcs-url/org-name vcs-url)
                                                                     :repo (vcs-url/repo-name vcs-url)})}
                    [:i.fa.fa-gear]]
                   " "
                   [:a.github-icon-link {:href vcs-url}
                    [:i.fa.fa-github]]]]])])]])))))

(defn plans-trial-notification [plan org-name controls-ch]
  [:div.row-fluid
   [:div.alert.alert-success {:class (when (plan-model/trial-over? plan) "alert-error")}
    [:p
     (if (plan-model/trial-over? plan)
       "Your 2-week trial is over!"

       [:span "The " [:strong org-name] " organization has "
        (plan-model/pretty-trial-time plan) " left in its trial."])]
    [:p
     "The trial plan is equivalent to the Solo plan with 6 containers."]
    (when (and (not (:too_many_extensions plan))
               (> 3 (plan-model/days-left-in-trial plan)))
      [:p
       "Need more time to decide? "
       (forms/stateful-button
        [:button.btn.btn-mini.btn-success
         {:data-success-text "Extended!",
          :data-loading-text "Extending...",
          :on-click #(put! controls-ch [:extend-trial-clicked {:org-name org-name}])}
         "Extend your trial"])])]])

(defn plans-piggieback-plan-notification [plan current-org-name]
  [:div.row-fluid
   [:div.offset1.span10
    [:div.alert.alert-success
     [:p
      "This organization is covered under " (:org_name plan) "'s plan which has "
      (:containers plan) " containers."]
     [:p
      "If you're an admin in the " (:org_name plan)
      " organization, then you can change plan settings from the "
      [:a {:href (routes/v1-org-settings-subpage {:org (:org_name plan)
                                                  :subpage "plan"})}
       (:org_name plan) " plan page"] "."]
     [:p
      "You can create a separate plan for " current-org-name " by selecting from the plans below."]]]])

(defn plan [app owner]
  (reify

    ;; We're loading Checkout here because the loading status is not something
    ;; that we can hope to serialize into the state. It will be stale when we
    ;; move to a different browser or auto-refresh the page.
    ;; Making the component responsible for loading Checkout seems like the best
    ;; way to make sure it's loaded when we need it.
    om/IInitState
    (init-state [_]
      {:checkout-loaded? (stripe/checkout-loaded?)
       :checkout-loaded-chan (chan)})
    om/IWillMount
    (will-mount [_]
      (let [ch (om/get-state owner [:checkout-loaded-chan])
            checkout-loaded? (om/get-state owner [:checkout-loaded?])]
        (when-not checkout-loaded?
          (go (<! ch) ;; wait for success message
              (utils/mlog "Stripe checkout loaded")
              (om/set-state! owner [:checkout-loaded?] true))
          (utils/mlog "Loading Stripe checkout")
          (stripe/load-checkout ch))))
    om/IWillUnmount
    (will-unmount [_]
      (close! (om/get-state owner [:checkout-loaded-chan])))

    om/IRenderState
    (render-state [_ {:keys [checkout-loaded?]}]
      (let [plan (get-in app state/org-plan-path)
            org-name (get-in app state/org-name-path)
            controls-ch (om/get-shared owner [:comms :controls])]
        (html
         (if-not (and plan checkout-loaded?)
           [:div.loading-spinner common/spinner]

           [:div#billing.plans.pricing.row-fluid
            (when (plan-model/trial? plan)
              (plans-trial-notification plan org-name controls-ch))
            (when (plan-model/piggieback? plan org-name)
              (plans-piggieback-plan-notification plan org-name))
            (om/build plans-component/plans app)
            (shared/customers-trust)
            (om/build plans-component/pricing-features app)
            plans-component/pricing-faq]))))))

(defn containers [app owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (utils/tooltip "#grandfathered-tooltip-hack") {:animation false})
    om/IRender
    (render [_]
      (let [plan (get-in app state/org-plan-path)
            selected-containers (or (get-in app state/selected-containers-path)
                                    (:containers plan))
            controls-ch (om/get-shared owner [:comms :controls])
            old-total (plan-model/stripe-cost plan)
            new-total (plan-model/cost (:template_properties plan) selected-containers)]
        (html
         (if-not plan
           [:div.loading-spinner common/spinner]

           [:div#edit-plan
            [:fieldset
             [:legend
              "Our pricing is flexible and scales with you. Add as many containers as you want for $"
              (get-in plan [:template_properties :container_cost])
              "/month each."]
             [:div.main-content
              [:div.left-section
               [:div.plan
                [:h2 "Your Current Plan"]
                [:p
                 [:strong "$" old-total] "/ month"]
                [:ul [:li "Includes " (:containers plan) " containers"]
                 [:li "Additional containers for $"
                  (get-in plan [:template_properties :container_cost]) "/month"]
                 [:li [:strong "No other limits"]]]]]
              [:div.right-section
               [:h3 "New total: $" new-total]
               [:h4
                "Old total: $" old-total
                (when (plan-model/grandfathered? plan)
                  [:span.grandfather
                   "(grandfathered"
                   [:i.fa.fa-question-circle#grandfathered-tooltip-hack
                    {:title: "We've changed plan prices since you signed up, so you're grandfathered in at the old price!"}]
                   ")"])]
               [:form
                [:div.container-picker
                 [:p "You can add or remove containers below; more containers means faster builds and lower queue times."]
                 [:div.container-slider
                  [:span (get-in plan [:template_properties :free_containers])]
                  (let [max (if (< selected-containers 80)
                              80
                              (let [num (+ 80 selected-containers)]
                                (+ num (- 10 (mod num 10)))))]
                    (list
                     [:input#rangevalue
                      {:type "range"
                       :value selected-containers
                       :min (get-in plan [:template_properties :free_containers])
                       :max max
                       :on-change #(utils/edit-input controls-ch state/selected-containers-path %
                                                     :value (int (.. % -target -value)))}]
                     [:span max]))]
                 [:div.container-input
                  [:input
                   {:type "text"
                    :value selected-containers
                    :on-change #(utils/edit-input controls-ch state/selected-containers-path %
                                                  :value (int (.. % -target -value)))}]]]
                [:fieldset
                 (forms/managed-button
                  [:button.btn.btn-large.btn-primary.center
                   {:data-success-text "Saved",
                    :data-loading-text "Saving...",
                    :type "submit"
                    :on-click #(do (put! controls-ch [:update-containers-clicked {:containers selected-containers}])
                                   false)}
                   "Update plan"])
                 (when (< old-total new-total)
                   [:span.help-block
                    "We'll charge your card today, for the prorated difference between your new and old plans."])
                 (when (> old-total new-total)
                   [:span.help-block
                    "We'll credit your account, for the prorated difference between your new and old plans."])]]]]
             plans-component/pricing-faq]]))))))

(defn piggyback-organizations [app owner]
  (om/component
   (html
    (let [org-name (get-in app state/org-name-path)
          user-login (:login (get-in app state/user-path))
          user-orgs (get-in app state/user-organizations-path)
          plan (get-in app state/org-plan-path)
          ;; orgs that this user can add to piggyback orgs
          elligible-piggyback-orgs (-> (map :login user-orgs)
                                       (set)
                                       (conj user-login)
                                       (disj org-name)
                                       (sort))
          ;; This lets users toggle selected piggyback orgs that are already in the plan. Merges:
          ;; (:piggieback_orgs plan): ["org-a" "org-b"] with
          ;; selected-orgs:           {"org-a" false "org-c" true}
          ;; to return #{"org-b" "org-c"}
          selected-piggyback-orgs (set (keys (filter last
                                                     (merge (zipmap (:piggieback_orgs plan) (repeat true))
                                                            (get-in app state/selected-piggyback-orgs-path)))))
          controls-ch (om/get-shared owner [:comms :controls])]
      [:div.row-fluid
       [:div.span8
        [:fieldset
         [:legend "Extra organizations"]
         [:p
          "Your plan covers all repositories (including forks) in the "
          [:strong org-name]
          " organization by default."]
         [:p "You can let any GitHub organization you belong to, including personal accounts, piggyback on your plan. Projects in your piggyback organizations will be able to run builds on your plan."]
         [:p
          [:span.label.label-info "Note:"]
          " Members of the piggyback organizations will be able to see that you're paying for them, the name of your plan, and the number of containers you've paid for. They won't be able to edit the plan unless they are also admins on the " org-name " org."]
         (if-not user-orgs
           [:div "Loading organization list..."]
           [:div.row-fluid
            [:div.span12
             [:form
              [:div.controls
               ;; orgs that this user can add to piggyback orgs and existing piggyback orgs
               (for [org (clojure.set/union elligible-piggyback-orgs (set (:piggieback_orgs plan)))]
                 [:div.control
                  [:label.checkbox
                   [:input
                    (let [checked? (contains? selected-piggyback-orgs org)]
                      {:value org
                       :checked checked?
                       :on-change #(utils/edit-input controls-ch (conj state/selected-piggyback-orgs-path org) % :value (not checked?))
                       :type "checkbox"})]
                   org]])]
              [:div.form-actions.span7
               (forms/managed-button
                [:button.btn.btn-large.btn-primary
                 {:data-success-text "Saved",
                  :data-loading-text "Saving...",
                  :type "submit",
                  :on-click #(do (put! controls-ch [:save-piggyback-orgs-clicked {:org-name org-name
                                                                                  :selected-piggyback-orgs selected-piggyback-orgs}])
                                 false)}
                 "Also pay for these organizations"])]]]])]]]))))

(defn transfer-organizations [app owner]
  (om/component
   (html
    (let [org-name (get-in app state/org-name-path)
          user-login (:login (get-in app state/user-path))
          user-orgs (get-in app state/user-organizations-path)
          elligible-transfer-orgs (-> (map :login user-orgs)
                                      (set)
                                      (conj user-login)
                                      (disj org-name)
                                      (sort))
          plan (get-in app state/org-plan-path)
          selected-transfer-org (get-in app state/selected-transfer-org-path)
          controls-ch (om/get-shared owner [:comms :controls])]
      [:div.row-fluid
       [:div.span8
        [:fieldset
         [:legend "Transfer plan to a different organization"]
         [:div.alert.alert-warning
          [:strong "Warning!"]
          [:p "If you're not an admin on the "
           (if (seq selected-transfer-org)
             (str selected-transfer-org " organization,")
             "organization you transfer to,")
           " then you won't be able to transfer the plan back or edit the plan."]
          [:p
           "The transferred plan will be extended to include the "
           org-name " organization, so your builds will continue to run. Only admins of the "
           (if (seq selected-transfer-org)
             (str selected-transfer-org " org")
             "organization you transfer to")
           " will be able to edit the plan."]]
         (if-not user-orgs
           [:div "Loading organization list..."]
           [:div.row-fluid
            [:div.span12
             [:div
              [:form
               [:div.controls
                (for [org elligible-transfer-orgs]
                  [:div.control
                   [:label.radio {:name org}
                    [:input {:value org
                             :checked (= org selected-transfer-org)
                             :on-change #(utils/edit-input controls-ch state/selected-transfer-org-path %)
                             :type "radio"}]
                    org]])]
               [:div.form-actions.span6
                (forms/managed-button
                 [:button.btn.btn-danger.btn-large
                  {:data-success-text "Transferred",
                   :data-loading-text "Tranferring...",
                   :type "submit",
                   :class (when (empty? selected-transfer-org) "disabled")
                   :on-click #(do (put! controls-ch [:transfer-plan-clicked {:org-name org-name
                                                                             :to selected-transfer-org}])
                                  false)
                   :data-bind
                   "click: transferPlan, enable: transfer_org_name(), text: transfer_plan_button_text()"}
                  "Transfer plan" (when (seq selected-transfer-org) (str " to " selected-transfer-org))])]]]]])]]]))))

(defn organizations [app owner]
  (om/component
   (html
    [:div
     (om/build piggyback-organizations app)
     (om/build transfer-organizations app)])))

(defn- billing-card [app owner]
  (reify
    om/IInitState
    (init-state [_]
      {:checkout-loaded? (stripe/checkout-loaded?)
       :checkout-loaded-chan (chan)})
    om/IWillMount
    (will-mount [_]
      (let [ch (om/get-state owner [:checkout-loaded-chan])
            checkout-loaded? (om/get-state owner [:checkout-loaded?])]
        (when-not checkout-loaded?
          (go (<! ch) ;; wait for success message
              (utils/mlog "Stripe checkout loaded")
              (om/set-state! owner [:checkout-loaded?] true))
          (utils/mlog "Loading Stripe checkout")
          (stripe/load-checkout ch))))
    om/IWillUnmount
    (will-unmount [_]
      (close! (om/get-state owner [:checkout-loaded-chan])))
    om/IRenderState
    (render-state [_ {:keys [checkout-loaded?]}]
      (html
        (let [card (get-in app state/stripe-card-path)
              controls-ch (om/get-shared owner [:comms :controls])]
          (if-not (and card checkout-loaded?)
            [:div.card.row-fluid [:legend.span8 "Card on file"]
             [:div.row-fluid [:div.offset1.span6 [:div.loading-spinner common/spinner]]]]
            [:div
              [:div.card.row-fluid [:legend.span8 "Card on file"]]
              [:div.row-fluid
               [:div.offset1.span6
                [:table.table.table-condensed
                 {:data-bind "with: cardInfo"}
                 [:thead
                  [:th "Name"]
                  [:th "Card type"]
                  [:th "Card Number"]
                  [:th "Expiry"]]
                 [:tbody
                  [:tr
                   [:td (:name card)]
                   [:td (:type card)]
                   [:td "xxxx-xxxx-xxxx-" (:last4 card)]
                   [:td (gstring/format "%02d" (:exp_month card)) \/ (:exp_year card)]]]]]]
              [:div.row-fluid
               [:div.offset1.span7
                [:form.form-horizontal
                 [:div.control-group
                  [:div.control
                   (forms/managed-button
                     [:button#charge-button.btn.btn-primary.submit-button
                      {:data-success-text "Success",
                       :data-failed-text "Failed",
                       :data-loading-text "Updating",
                       :on-click #(do (put! controls-ch [:update-card-clicked])
                                      false)
                       :type "submit"}
                      "Change credit card"])]]]]]]))))))

;; Render a friendly human-readable version of a Stripe discount coupon.
;; Stripe has a convention for this that does not seem to be documented, so we
;; reverse engineer it here.
;; Examples from Stripe are:
;;     100% off for 1 month
;;     100% off for 6 months
;;  $100.00 off for 6 months
;;   $19.00 off for 12 months
;;      25% off forever
(defn format-discount
  [plan]
  (let [{ duration-in-months :duration_in_months
          percent-off        :percent_off
          amount-off         :amount_off
          duration           :duration
          id                 :id}  (get-in plan [:discount :coupon])
        discount-amount (if percent-off
                          (str percent-off "%")
                          (gstring/format "$%.2f" (/ amount-off 100)))
        discount-period (cond (= duration "forever") "forever"
                              (= duration-in-months 1) "for 1 month"
                              :else (gstring/format "for %d months" duration-in-months))]
    [:p "Your plan includes " discount-amount " off " discount-period
        " from coupon code " [:strong id]]))

;; Show a 'Discount' section showing any Stripe discounts that are being appied
;; the current plan.
;; Important: If there are no discounts, we don't want to show anything;
;; we do not want to tempt happy, paying customers to search online for discount
;; codes.
(defn- billing-discounts [app owner]
  (reify
    om/IRender
    (render [_]
      (html
        (let [plan (get-in app state/org-plan-path)]
          [:div.row-fluid
            (when (plan-model/has-active-discount? plan)
              [:fieldset
                [:legend.span8 "Discounts"]
                [:div.span8 (format-discount plan)]])])))))

(defn- billing-invoice-data [app owner]
  (reify
    om/IRender
    (render [_]
      (html
        (let [controls-ch (om/get-shared owner [:comms :controls])
              plan-data (get-in app state/org-plan-path)
              settings (state-utils/merge-inputs plan-data
                                                 (inputs/get-inputs-from-app-state owner)
                                                 [:billing_email :billing_name :extra_billing_data])]
          (if-not plan-data
            [:div.invoice-data.row-fluid
             [:legend.span8 "Invoice data"]
             [:div.row-fluid [:div.span8 [:div.loading-spinner common/spinner]]]]
            [:div.invoice-data.row-fluid
             [:fieldset
              [:legend.span8 "Invoice data"]
              [:form.form-horizontal.span8
               [:div.control-group
                [:label.control-label {:for "billing_email"} "Billing email"]
                [:div.controls
                 [:input.span10
                  {:value (str (:billing_email settings))
                   :name "billing_email",
                   :type "text"
                   :on-change #(utils/edit-input controls-ch (conj state/inputs-path :billing_email) %)}]]]
               [:div.control-group
                [:label.control-label {:for "billing_name"} "Billing name"]
                [:div.controls
                 [:input.span10
                  {:value (str (:billing_name settings))
                   :name "billing_name",
                   :type "text"
                   :on-change #(utils/edit-input controls-ch (conj state/inputs-path :billing_name) %)}]]]
               [:div.control-group
                [:label.control-label
                 {:for "extra_billing_data"}
                 "Extra data to include in your invoice"]
                [:div.controls
                 [:textarea.span10
                  {:value (str (:extra_billing_data settings))
                   :placeholder
                   "Extra information you would like us to include in your invoice, e.g. your company address or VAT ID.",
                   :rows 3
                   :name "extra_billing_data"
                   ;; FIXME These edits are painfully slow with the whitespace compiled Javascript
                   :on-change #(utils/edit-input controls-ch (conj state/inputs-path :extra_billing_data) %)}]]]
               [:div.control-group
                [:div.controls
                 (forms/managed-button
                   [:button.btn.btn-primary
                    {:data-success-text "Saved invoice data",
                     :data-loading-text "Saving invoice data...",
                     :on-click #(do (put! controls-ch [:save-invoice-data-clicked])
                                    false)
                     :type "submit",}
                    "Save invoice data"])]]]]]))))))

(defn- invoice-total
  [invoice]
  (/ (:amount_due invoice) 100))

(defn- stripe-ts->date
  [ts]
  (datetime/year-month-day-date (* 1000 ts)))

(defn invoice-view
  "Render an invoice table row.
  Invoices fetched from the API look like:

  ;; Invoice API Format
  ;; ------------------
  ;; amount_due: 3206
  ;; currency: \"usd\"
  ;; date: 1403535350
  ;; id: \"in_2398vhs098AHYoi\"
  ;; paid: true
  ;; period_end: 1403535350
  ;; period_start: 1402665929"
  [invoice owner]
  (reify
    om/IRender
    (render [_]
      (html
        (let [controls-ch (om/get-shared owner [:comms :controls])
              invoice-id (:id invoice)]
          [:tr
            [:td (stripe-ts->date (:date invoice))]
            [:td (str (stripe-ts->date (:period_start invoice)))
                      " - "
                      (stripe-ts->date (:period_end invoice))]
            [:td.numeric (gstring/format "$%.2f" (invoice-total invoice))]
            [:td
              [:span
                (forms/managed-button
                  [:button.btn.btn-mini.btn-primary
                    {:data-failed-text "Failed",
                     :data-success-text "Sent",
                     :data-loading-text "Sending...",
                     :on-click #(do (put! controls-ch [:resend-invoice-clicked
                                                       {:invoice-id invoice-id}])
                                    false)}
                    "Resend"])]]])))))

(defn- ->balance-string [balance]
  (let [suffix (cond
                (< balance 0) " in credit."
                (> balance 0) " payment outstanding."
                :else "")
        amount (-> balance Math/abs (/ 100) .toLocaleString)]
    (str "$" amount suffix)))

(defn- billing-invoices [app owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (utils/popover "#invoice-popover-hack"
                     {:animation false
                      :trigger "hover"
                      :html true})
      (utils/tooltip "#resend-invoice-tooltip-hack"
                     {:animation false}))
    om/IRender
    (render [_]
      (html
        (let [account-balance (get-in app state/org-plan-balance-path)
              invoices (get-in app state/org-invoices-path)]
          (if-not (and account-balance invoices)
            [:div.row-fluid
             [:div.span8
               [:legend "Invoices"]
              [:div.loading-spinner common/spinner]]]
            [:div.row-fluid
             [:div.span8
               [:legend "Invoices"]
               [:dl.dl-horizontal
                [:dt
                 "Account balance"
                 [:i.fa.fa-question-circle#invoice-popover-hack
                  {:title "Account balance"
                   :data-content (str "<p>This is the credit you have with Circle. If your credit is positive, then we will use it before charging your credit card.</p>"
                                      "<p>Contact us if you'd like us to send you a refund for the balance.</p>"
                                      "<p>This amount may take a few hours to refresh.</p>")}]]
                [:dd
                 [:span (->balance-string account-balance)]]]
               [:table.table.table-bordered.table-striped
                [:thead
                 [:tr
                  [:th "Invoice date"]
                  [:th "Time period covered"]
                  [:th "Total"]
                  [:th
                   [:i.fa.fa-question-circle#resend-invoice-tooltip-hack
                    {:title "Resend an invoice to the billing email above."}]
                   "Actions"]]]
                [:tbody
                 (om/build-all invoice-view invoices)]]]]))))))

(defn billing [app owner]
  (reify
    om/IRender
    (render [_]
      (html
        [:div.plans
          (om/build billing-card app)
          (om/build billing-invoice-data app)
          (om/build billing-discounts app)
          (om/build billing-invoices app)]))))

(defn cancel [app owner]
  (reify
    om/IRender
    (render [_]
      (let [controls-ch (om/get-shared owner [:comms :controls])
            org-name (get-in app state/org-name-path)]
        (html
         [:div.org-cancel
          [:div.row-fluid [:fieldset [:legend "Cancel"]]]
          [:div.row-fluid
           [:h3
            {:data-bind "attr: {alt: cancelFormErrorText}"}
            "Please tell us why you're canceling. This helps us make Circle better!"]
           [:form
            (for [reason [{:value "project-ended", :text "Project Ended"},
                          {:value "slow-performance", :text "Slow Performance"},
                          {:value "unreliable-performance", :text "Unreliable Performance"},
                          {:value "too-expensive", :text "Too Expensive"},
                          {:value "didnt-work", :text "Couldn't Make it Work"},
                          {:value "missing-feature", :text "Missing Feature"},
                          {:value "poor-support", :text "Poor Support"},
                          {:value "other", :text "Other"}]]
              [:label.cancel-reason
               [:input
                {:checked (get-in app (state/selected-cancel-reason-path (:value reason)))
                 :on-change #(utils/toggle-input controls-ch (state/selected-cancel-reason-path (:value reason)) %)
                 :type "checkbox"}]
               (:text reason)])
            [:textarea
             {:required true
              :value (get-in app state/cancel-notes-path)
              :on-change #(utils/edit-input controls-ch state/cancel-notes-path %)}]
            [:label
             {:placeholder "Thanks for the feedback!",
              :alt (if (get app (state/selected-cancel-reason-path "other"))
                     "Would you mind elaborating more?"
                     "Have any other thoughts?")}]
            (let [reasons (->> (get-in app state/selected-cancel-reasons-path)
                                                    (filter second)
                                                    keys
                                                    set)
                  notes (get-in app state/cancel-notes-path)
                  errors (cond (empty? reasons) "Please select at least one reason."
                               (and (contains? reasons "other") (string/blank? notes)) "Please specify above."
                               :else nil)]
              ;; This is a bit of a hack -- it could be much nicer if managed button exposed more of its interface
              ;; or accepted hooks
              (if errors
                (list
                 (when (om/get-state owner [:show-errors?])
                   [:div.hint {:class "show"} [:i.fa.fa-exclamation-circle] " " errors])
                 [:button {:on-click #(do (om/set-state! owner [:show-errors?] true) false)}
                  "Cancel Plan"])
                (forms/managed-button
                 [:button {:data-spinner "true"
                           :on-click #(do (put! controls-ch [:cancel-plan-clicked {:org-name org-name
                                                                                   :cancel-reasons reasons
                                                                                   :cancel-notes notes}])
                                          false)}
                  "Cancel Plan"])))]]])))))

(def main-component
  {:users users
   :projects projects
   :plan plan
   :containers containers
   :organizations organizations
   :billing billing
   :cancel cancel})

(defn determine-subpage
  "Determines which subpage we should show the user. If they have
   a plan, then we don't want to show them the plan page; if they
   don't have a plan, then we don't want to show them the invoices page."
  [subpage plan org-name]
  (cond (#{:users :projects} subpage)
        subpage

        (and plan
             (plan-model/can-edit-plan? plan org-name)
             (= subpage :plan))
        :containers

        (and plan
             (not (plan-model/can-edit-plan? plan org-name))
             (#{:containers :organizations :billing :cancel} subpage))
        :plan

        :else subpage))

(defn org-settings [app owner]
  (reify
    om/IRender
    (render [_]
      (let [requested-subpage (get app :org-settings-subpage :projects)
            org-data (get-in app state/org-data-path)
            plan (get-in app state/org-plan-path)
            subpage (determine-subpage requested-subpage plan (:name org-data))]
        (html [:div.container-fluid.org-page
               (if-not (:loaded org-data)
                 [:div.loading-spinner common/spinner]
                 [:div.row-fluid
                  (om/build sidebar {:subpage subpage :plan plan :org-name (:name org-data)})
                  [:div.span9
                   (om/build common/flashes (get-in app state/error-message-path))
                   [:div#subpage
                    [:div
                     (if (:authorized? org-data)
                       (om/build (get main-component subpage projects) app)
                       [:div (om/build non-admin-plan
                                       {:login (get-in app [:current-user :login])
                                        :org-name (:org-settings-org-name app)
                                        :subpage subpage})])]]]])])))))
