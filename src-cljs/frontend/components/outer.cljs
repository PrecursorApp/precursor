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
            [frontend.config :as config]
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

(defn submit-early-access-form [owner]
  (go
    (om/update-state! owner (fn [s]
                              (assoc s :submitting? true :error nil)))
    ;; we wouldn't typically use ajax in a component--it's not advisable in
    ;; this case, but we're short on time
    ;; important to get the state out of owner since we're not re-rendering on update
    (let [{:keys [subdomain]} (om/get-state owner)
          res (<! (ajax/managed-ajax :post "/api/v1/create-team" :params {:subdomain subdomain}))]
      (if (= :success (:status res))
        (om/update-state! owner (fn [s]
                                  (assoc s
                                         :submitting? false
                                         :submitted? true
                                         :error nil
                                         :team-created? true
                                         :team (:team res))))
        (do
          ;; handle already taken subdomains
          (om/update-state!
           owner
           (fn [s]
             (assoc s
                    :submitting? false
                    :error [:p (:msg (:response res))])))
          (put! (om/get-shared owner [:comms :errors]) [:api-error res]))))))

(defn signup [app owner]
  (reify
    om/IInitState (init-state [_] {:subdomain ""
                                   :error nil
                                   :submitting? false
                                   :submitted? false
                                   :team-created? false
                                   :team nil})
    om/IDisplayName (display-name [_] "")
    om/IRenderState
    (render-state [_ {:keys [subdomain submitting? error submitted? team-created? team]}]
      (let [{:keys [cast! handlers]} (om/get-shared owner)
            disabled? (or submitting? (not (utils/logged-in? owner)))]
        (html
         [:div.early-access {:class (str (get-in app [:navigation-data :type] " team ")
                                         (when team-created? " granted "))}
          [:div.content
           [:div.early-access-info
            [:h2.early-access-heading
             "We're excited to show you the team features we're building."]

            [:p.early-access-copy
             "To activate your trial, please "
             (if (utils/logged-in? owner)
               "take a moment to"
               "sign in first and")
             " choose a subdomain."
             ]
            (when (utils/logged-in? owner)
              [:p.early-access-copy
               "It should be at least 4 characters and start with a letter. You can use letters, numbers, and hyphens."])
            (when-not (utils/logged-in? owner)
              [:div.early-access-sign
               (om/build common/google-login {:source "Early Access Form"})])]
           [:div.early-access-form {:class (str (when disabled? "disabled ")
                                                (when submitting? "submitting ")
                                                (when submitted? "submitted "))}
            [:div.subdomain-input
             [:div.subdomain
              {:tab-index "1"
               :ref "subdomain"
               :data-before "team"
               :content-editable true
               :on-key-down #(when (= "Enter" (.-key %))
                               (.preventDefault %)
                               ;; If they hit enter, submit the form
                               (submit-early-access-form owner))
               :on-input #(om/set-state-nr! owner :subdomain (goog.dom/getRawTextContent (.-target %)))}
              subdomain]
             [:div {:style {:display "inline-block"}}
              ".precursorapp.com"]]
            [:button.early-access-button {:tab-index "5"
                                          :ref "submit-button"
                                          :disabled (or disabled? submitted?)
                                          :on-click #(submit-early-access-form owner)}
             (cond submitting?
                   (html
                    [:span "Setting up your team"
                     [:i.loading-ellipses
                      [:i "."]
                      [:i "."]
                      [:i "."]]])

                   submitted? "Thanks, your subdomain is ready!"

                   :else "Create your team")]

            (when error
              [:div.error error])

            (when team-created?
              [:div.early-access-granted
               [:p "Your team is set up at "
                [:a {:href (str (url/map->URL {:host (str (:team/subdomain team) "." config/hostname)
                                               :protocol config/scheme
                                               :port config/port
                                               :path (str "/document/" (:team/intro-doc team))
                                               :query {:overlay "team-settings"}}))
                     :target "_self"}
                 (str (url/map->URL {:host (str (:team/subdomain team) "." config/hostname)
                                     :protocol config/scheme
                                     :port config/port
                                     :path "/"}))]
                ". You'll be prompted to log in, then you can invite your teammates."]

               [:p "Documents you create in your subdomain are private by default and shared with all of your teammates."]

               [:p "You'll have two weeks of free, unlimited access, and then we'll follow up with you to see how things are going."]])]]])))))

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
                 :title "Start trial"
                 :role "button"}
                "Start trial"]]]
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
    [:div.nav.nav-head
     [:a.nav-link.nav-logo
      (merge {:href "/"
              :title "Precursor"}
             (when (utils/logged-in? owner)
               {:on-click #((om/get-shared owner :cast!) :launch-app-clicked {:analytics-data {:source "top-left-nav"}})}))
      "Precursor"]
     [:a.nav-link.nav-home
      {:href "/home"
       :title "Home"}
      "Home"]
     [:a.nav-link.nav-pricing
      {:href "/pricing"
       :title "Pricing"}
      "Pricing"]
     [:a.nav-link.nav-blog
      {:href "/blog"
       :title "Blog"
       :target "_self"}
      "Blog"]
     (if (utils/logged-in? owner)
       [:a.nav-link.nav-app
        {:role "button"
         :title "Launch Precursor"
         :on-click #((om/get-shared owner :cast!) :launch-app-clicked {:analytics-data {:source "top-right-nav"}})}
        "App"]
       [:div.nav-link.nav-google
        (om/build common/google-login {:source "Nav" :size :small})])])))

(defn nav-foot [app owner]
  (om/component
   (html
    [:div.nav.nav-foot
     [:a.nav-link.nav-logo
      (merge {:title "Precursor"
              :href "/"}
             (when (utils/logged-in? owner)
               {:on-click #((om/get-shared owner :cast!) :launch-app-clicked {:analytics-data {:source "bottom-nav-logo"}})}))
      (common/icon :logomark)]
     [:a.nav-link.nav-home
      {:title "Home"
       :href "/home"}
      "Home"]
     [:a.nav-link.nav-pricing
      {:title "Pricing"
       :href "/pricing"}
      "Pricing"]
     [:a.nav-link.nav-blog
      {:title "Blog"
       :href "/blog"
       :target "_self"}
      "Blog"]
     (if (utils/logged-in? owner)
       [:a.nav-link.nav-app
        {:title "Launch Precursor"
         :on-click #((om/get-shared owner :cast!) :launch-app-clicked {:analytics-data {:source "bottom-nav"}})
         :role "button"}
        "App"]
       [:a.nav-link {:title "Sign in with Google"
                     :href (auth/auth-url :source "bottom-nav")
                     :role "button"}
        "Sign in"])
     [:a.nav-link.nav-twitter
      {:title "@PrecursorApp"
       :href "https://twitter.com/PrecursorApp"}
      (common/icon :twitter)]])))

(def outer-components
  {:landing landing/landing
   :pricing pricing
   :early-access signup})

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
           (om/build component app)
           [:div.outer-foot (om/build nav-foot {})]])))))
