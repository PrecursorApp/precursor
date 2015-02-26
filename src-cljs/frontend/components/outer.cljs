(ns frontend.components.outer
  (:require [cemerick.url :as url]
            [clojure.set :as set]
            [clojure.string :as str]
            [datascript :as d]
            [frontend.analytics :as analytics]
            [frontend.async :refer [put!]]
            [frontend.auth :as auth]
            [frontend.components.common :as common]
            [frontend.components.doc-viewer :as doc-viewer]
            [frontend.components.document-access :as document-access]
            [frontend.components.landing :as landing]
            [frontend.datascript :as ds]
            [frontend.models.doc :as doc-model]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.ajax :as ajax]
            [frontend.utils.date :refer (date->bucket)]
            [goog.dom]
            [goog.labs.userAgent.browser :as ua]
            [goog.string :as gstring]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [sablono.core :refer (html)]
                   [cljs.core.async.macros :as am :refer [go go-loop alt!]])
  (:import [goog.ui IdGenerator]))

(defn early-access [app owner]
  (reify
    om/IInitState (init-state [_] {:company-name ""
                                   :employee-count ""
                                   :use-case ""
                                   :error nil
                                   :submitting? false
                                   :submitted? false})
    om/IDisplayName (display-name [_] "Early Access")
    om/IRenderState
    (render-state [_ {:keys [company-name employee-count use-case submitting? error submitted?]}]
      (let [{:keys [cast! handlers]} (om/get-shared owner)
            disabled? (or submitting? (not (utils/logged-in? owner)))
            submit-form
            (fn [params]
              (go
                (om/update-state! owner (fn [s]
                                          (assoc s :submitting? true :error nil)))
                ;; we wouldn't typically use ajax in a component--it's not advisable in
                ;; this case, but we're short on time
                (let [res (<! (ajax/managed-ajax :post "/api/v1/early-access" :params params))]
                  (if (= :success (:status (utils/inspect res)))
                    (om/update-state! owner (fn [s]
                                              (assoc s
                                                     :submitting? false
                                                     :submitted? true
                                                     :error nil
                                                     :company-name ""
                                                     :employee-count ""
                                                     :use-case "")))
                    (do
                      (om/update-state!
                       owner
                       (fn [s]
                         (assoc s
                                :submitting? false

                                :error
                                [:p "Oops that didn't work! "
                                 [:a {:on-click #(.stopPropagation %)
                                      :href
                                      (str "mailto:hi@precursorapp.com?"
                                           (url/map->query {:subject "Early Access to Precursor"
                                                            :body (str "Company Name\n"
                                                                       company-name
                                                                       "\n\nTeam Size\n"
                                                                       employee-count
                                                                       "\n\nUse Case\n"
                                                                       use-case)}))}
                                  "Try this email."]
                                 ""])))
                      (put! (om/get-shared owner [:comms :errors]) [:api-error res]))))))]
        (html
         [:div.early-access {:class (get-in app [:navigation-data :type] "team")}
          [:div.content

           [:div.early-access-info
            [:h2.early-access-heading
             "We're excited to show you the team features we're building."]
            [:p.early-access-copy
             "To activate your early access, please "
             (if (utils/logged-in? owner)
               "take a moment to"
               "sign in first and")
             " fill out the following information.
              We'll send you an email confirmation once your account has been granted full access."]
            (when-not (utils/logged-in? owner)
              [:div.calls-to-action
               (om/build common/google-login {:source "Early Access Form"})])]

           ;; need to hook up disabled class
           [:div.early-access-form {:class (str (when disabled? "disabled ")
                                                (when submitting? "submitting ")
                                                (when submitted? "submitted ")
                                                (when error "error "))}
            [:div.adaptive-placeholder {:tab-index "2"
                                        :ref "company-name"
                                        :data-before "What's your company's name?"
                                        :data-after "Company Name"
                                        :content-editable true
                                        :on-key-down #(when (= "Enter" (.-key %))
                                                        (.preventDefault %)
                                                        ;; If they hit enter, send them to the next input.
                                                        (.focus (om/get-node owner "employee-count")))
                                        :on-input #(om/set-state! owner :company-name (goog.dom/getRawTextContent (.-target %)))}

             company-name]
            [:div.adaptive-placeholder {:tab-index "3"
                                        :ref "employee-count"
                                        :data-before "How many teammates do you have?"
                                        :data-after "Team Size"
                                        :content-editable true
                                        :on-key-down #(when (= "Enter" (.-key %))
                                                        (.preventDefault %)
                                                        ;; If they hit enter, send them to the next input.
                                                        (.focus (om/get-node owner "use-case")))
                                        :on-input #(om/set-state! owner :employee-count (goog.dom/getRawTextContent (.-target %)))}
             employee-count]
            [:div.adaptive-placeholder {:tab-index "4"
                                        :ref "use-case"
                                        :data-before "How will you use Precursor?"
                                        :data-after "Use Case"
                                        :content-editable true
                                        :on-key-down #(when (= "Enter" (.-key %))
                                                        (.preventDefault %)
                                                        ;; If they hit enter, send them to the next input.
                                                        (.focus (om/get-node owner "submit-button"))
                                                        (.click (om/get-node owner "submit-button")))
                                        :on-input #(om/set-state! owner :use-case (goog.dom/getRawTextContent (.-target %)))}
             use-case]
            [:button.early-access-button {:tab-index "5"
                                          :ref "submit-button"
                                          :disabled (or disabled? submitted?)
                                          :on-click #(submit-form {:company-name company-name
                                                                   :employee-count employee-count
                                                                   :use-case use-case})}
             (cond submitting?
                   (html
                    [:span "Request is submitting"
                     [:i.loading-ellipses
                      [:i "."]
                      [:i "."]
                      [:i "."]]])

                   submitted?
                   "Got it! We'll respond soon."

                   (seq error)
                   error

                   :else "Request early access.")]]]])))))

(defn pricing [app owner]
  (reify
    om/IDisplayName (display-name [_] "Pricing Page")
    om/IRender
    (render [_]
      (let [{:keys [cast! handlers]} (om/get-shared owner)]
        (html
          [:div.pricing
           [:div.content
            [:div.price-blocks
             [:div.price-block.price-solo
              [:div.price-head
               [:h2.price-heading.content-copy {:title "Solo—freelancers, self-employed, etc."} "Solo"]]
              [:div.price-body
               ;; [:h4.content-copy "$10/mo"]    ; <- hold off until pricing is ready
               [:h4.content-copy "Pricing soon."] ; <- and delete this once it's ready
               [:p.price-copy.content-copy
                "Unlimited public docs, private docs, and project repos.
                Add additional teammates at any time and take full advantage of team features."]]
              [:div.price-foot
               [:a.price-button
                {:href "/early-access/solo"
                 :title "Try it free while we gather feedback."
                 :role "button"}
                "Request early access."]]]
             [:section.price-divide.left
              [:div.price-divide-line]]
             [:div.price-block.price-team
              [:div.price-head
               [:h2.price-heading.content-copy {:title "Team—startups, agencies, etc."} "Team"]]
              [:div.price-body
               ;; [:h4.content-copy "$10/mo/user"] ; <- hold off until pricing is ready
               [:h4.content-copy "Pricing soon."]   ; <- and delete this once it's ready
               [:p.price-copy.content-copy
                "Unlimited public docs, private docs, and project repos.
                Additional access to team-wide chat, in-app notifications, and file management."]]
              [:div.price-foot
               [:a.price-button
                {:href "/early-access/team"
                 :title "Try it free while we gather feedback."
                 :role "button"}
                "Request early access."]]]
             [:section.price-divide.right
              [:div.price-divide-line]]
             [:div.price-block.price-corp
              [:div.price-head
               [:h2.price-heading.content-copy {:title "Enterprise—large teams, industry leaders, etc."} "Enterprise"]]
              [:div.price-body
               [:h4.content-copy "Contact us."]
               [:p.price-copy.content-copy
                "Customized solutions designed to solve specific team constraints.
                E.g., integrations, custom servers, on-premise accommodations, etc."]]
              [:div.price-foot
               [:a.price-button
                {:href "mailto:enterprise@precursorapp.com?Subject=Enterprise%20Inquiry"
                 :title "We'll get back to you immediately."
                 :role "button"}
                "Contact us."]]]]]])))))

(defn nav-head [app owner]
  (om/component
   (html
    [:div.nav
     [:a.nav-link (merge {:href "/"
                          :role "button"
                          :title "Launch Precursor"}
                         (when (utils/logged-in? owner)
                           {:on-click #((om/get-shared owner :cast!) :launch-app-clicked {:analytics-data {:source "top-left-nav"}})}))
      "Precursor"]
     [:a.nav-link {:href "/home"
                   :role "button"
                   :title "Home"}
      "Home"]
     [:a.nav-link {:href "/pricing"
                   :role "button"
                   :title "Pricing"}
      "Pricing"]
     [:a.nav-link {:href "/blog"
                   :role "button"
                   :title "Blog"
                   :target "_self"}
      "Blog"]
     (if (utils/logged-in? owner)
       [:a.nav-link {:role "button"
                     :title "Launch Precursor"
                     :on-click #((om/get-shared owner :cast!) :launch-app-clicked {:analytics-data {:source "top-right-nav"}})}
        "App"]
       [:div.nav-link
        (om/build common/google-login {:source "Nav" :size :small})])])))

(defn nav-foot [app owner]
  (om/component
   (html
    [:div.nav
     [:a.nav-link (merge {:title "Launch Precursor"
                          :href "/"
                          :role "button"}
                         (when (utils/logged-in? owner)
                           {:on-click #((om/get-shared owner :cast!) :launch-app-clicked {:analytics-data {:source "bottom-nav-logo"}})}))
      (common/icon :logomark)]
     [:a.nav-link {:title "Home"
                   :href "/home"
                   :role "button"}
      "Home"]
     [:a.nav-link {:title "Pricing"
                   :href "/pricing"
                   :role "button"}
      "Pricing"]
     [:a.nav-link {:title "Blog"
                   :href "/blog"
                   :role "button"
                   :target "_self"}
      "Blog"]
     (if (utils/logged-in? owner)
       [:a.nav-link {:title "Launch Precursor"
                     :on-click #((om/get-shared owner :cast!) :launch-app-clicked {:analytics-data {:source "bottom-nav"}})
                     :role "button"}
        "App"]
       [:a.nav-link {:title "Sign in with Google"
                     :href (auth/auth-url)
                     :on-click #(do (.preventDefault %)
                                    ((om/get-shared owner :cast!) :track-external-link-clicked
                                     {:path (auth/auth-url)
                                      :event "Signup Clicked"
                                      :properties {:source "bottom-nav"}}))
                     :role "button"}
        "Sign in"])
     [:a.nav-link {:title "Home"
                   :href "/home"
                   :role "button"}
      (common/icon :twitter)]])))

(def outer-components
  {:landing landing/landing
   :pricing pricing
   :early-access early-access
   })

(defn outer [app owner]
  (reify
    om/IDisplayName (display-name [_] "Outer")
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            nav-point (:navigation-point app)
            component (get outer-components nav-point)]
        (html
          [:div.outer {:class (concat [(str "page-" (name nav-point))]
                                      (when (= (:page-count app) 1) ["entry"])
                                      (when (utils/logged-in? owner) ["logged-in"]))}
           [:div.outer-head (om/build nav-head {})]
           (om/build component app {:react-key nav-point})
           [:div.outer-foot (om/build nav-foot {})]])))))
