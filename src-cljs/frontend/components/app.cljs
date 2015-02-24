(ns frontend.components.app
  (:require [cemerick.url :as url]
            [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.set :as set]
            [clojure.string :as str]
            [frontend.analytics :as analytics]
            [frontend.async :refer [put!]]
            [frontend.auth :as auth]
            [frontend.components.chat :as chat]
            [frontend.components.inspector :as inspector]
            [frontend.components.hud :as hud]
            [frontend.components.key-queue :as keyq]
            [frontend.components.canvas :as canvas]
            [frontend.components.common :as common]
            [frontend.components.landing :as landing]
            [frontend.components.drawing :as drawing]
            [frontend.components.overlay :as overlay]
            [frontend.cursors :as cursors]
            [frontend.favicon :as favicon]
            [frontend.overlay :refer [overlay-visible?]]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.seq :refer [dissoc-in select-in]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [ankha.core :as ankha])
  (:require-macros [sablono.core :refer (html)]))

(def keymap
  (atom nil))

(defn early-access [app owner]
  (reify
    om/IDisplayName (display-name [_] "Request Access")
    om/IRender
    (render [_]
      (let [{:keys [cast! handlers]} (om/get-shared owner)]
        (html

          ; [:div.early-access
          ;  [:div.early-access-content
          ;   [:div.early-access-info
          ;    [:h2.early-access-heading
          ;     "We're excited to show you our team features."]
          ;    [:p.early-access-copy
          ;     "To activate your early access, please sign in and let us know about the following info.
          ;     We'll send you an email confirmation once your account has been granted full access."]
          ;    [:div.calls-to-action
          ;      (common/google-login)]]
          ;   [:div.early-access-form ; needs class "disabled" when user is logged out to block interaction with form
          ;    [:div.adaptive-placeholder {:tab-index "2" :data-before "What's your company's name?" :data-after "Company Name" :content-editable ""}]
          ;    [:div.adaptive-placeholder {:tab-index "3" :data-before "How many employees are there?" :data-after "Employee Count" :content-editable ""}]
          ;    [:div.adaptive-placeholder {:tab-index "4" :data-before "How will you use Precursor?" :data-after "Use Case" :content-editable ""}]
          ;    [:button.early-access-button {:tab-index "5"} "Request early access."]]]]

          [:div.pricing
           [:div.content
            [:div.pricing-blocks
             [:div.pricing-block
              [:div.pricing-head
               [:h2.pricing-heading.content-copy {:title "Solo—freelancers, self-employed, etc."} "Solo"]]
              [:div.pricing-body
               ;; [:h4.content-copy "$10/mo"]    ; <- hold off until pricing is ready
               [:h4.content-copy "Coming soon."] ; <- and delete this once it's ready
               [:p.pricing-copy.content-copy
                "Unlimited public docs, private docs, and project repos.
                Add additional teammates at any time and take full advantage of team features."]]
              [:div.pricing-foot
               [:button.pricing-button
                {:title "Try it free while we gather feedback."}
                "Request early access."]]]
             [:div.pricing-divider
              [:div.pricing-divider-line]]
             [:div.pricing-block
              [:div.pricing-head
               [:h2.pricing-heading.content-copy {:title "Team—startups, agencies, etc."} "Team"]]
              [:div.pricing-body
               ;; [:h4.content-copy "$10/mo/user"] ; <- hold off until pricing is ready
               [:h4.content-copy "Coming soon."]   ; <- and delete this once it's ready
               [:p.pricing-copy.content-copy
                "Unlimited public docs, private docs, and project repos.
                Additional access to team-wide chat, in-app notifications, and file management."]]
              [:div.pricing-foot
               [:button.pricing-button
                {:title "Try it free while we gather feedback."}
                "Request early access."]]]
             [:div.pricing-divider
              [:div.pricing-divider-line]]
             [:div.pricing-block
              [:div.pricing-head
               [:h2.pricing-heading.content-copy {:title "Enterprise—large teams, industry leaders, etc."} "Enterprise"]]
              [:div.pricing-body
               [:h4.content-copy "Contact us."]
               [:p.pricing-copy.content-copy
                "Customized solutions designed to solve specific team constraints.
                E.g., integrations, custom servers, on-premise accommodations, etc."]]
              [:div.pricing-foot
               [:button.pricing-button
                {:title "We'll get back to you immediately."}
                "Contact us."]]]]]]

          )))))

(defn app* [app owner]
  (reify
    om/IDisplayName (display-name [_] "App*")
    om/IRender
    (render [_]
      (let [{:keys [cast! handlers]} (om/get-shared owner)
            chat-opened? (get-in app state/chat-opened-path)
            right-click-learned? (get-in app state/right-click-learned-path)]

        (if (:navigation-point app)
          (html
           [:div#app.app
            ; (om/build early-access app)
            (when (:show-landing? app)
              (om/build landing/landing (select-keys app [:show-landing? :document/id])
                        {:react-key "landing"}))

            (when (= :root (:navigation-point app))
              (om/build drawing/landing-background {:db/id (:document/id app)} {:react-key "landing-background"}))

            (when (overlay-visible? app)
              (om/build overlay/overlay app {:react-key "overlay"}))

            [:div.inner {:on-click (when (overlay-visible? app)
                                     #(cast! :overlay-closed))
                         :class (when (empty? (:frontend-id-state app))
                                  "loading")
                         :key "inner"}
             [:style "#om-app:active{cursor:auto}"]
             (om/build canvas/canvas (select-in app [state/current-tool-path
                                                     [:drawing :in-progress?]
                                                     [:mouse-down]
                                                     [:layer-properties-menu]
                                                     [:menu]])
                       {:react-key "canvas"})

             (om/build chat/chat (select-in app [state/chat-opened-path
                                                 state/chat-mobile-opened-path
                                                 [:document/id]
                                                 [:sente-id]
                                                 [:client-id]])
                       {:react-key "chat"})

             (when (not right-click-learned?)
               (om/build canvas/radial-hint (select-in app [[:mouse-type]])
                         {:react-key "radial-hint"}))]

            (om/build hud/hud (select-in app [state/chat-opened-path
                                              state/overlays-path
                                              state/main-menu-learned-path
                                              state/chat-button-learned-path
                                              state/browser-settings-path
                                              [:document/id]
                                              [:subscribers :info]
                                              [:show-viewers?]
                                              [:client-id]
                                              [:cust]
                                              [:mouse-type]])
                      {:react-key "hud"})])
          (html [:div#app]))))))

(defn app [app owner]
  (reify
    om/IDisplayName (display-name [_] "App")
    om/IRender
    (render [_]
      (om/build app* (-> app
                       (dissoc :mouse)
                       (dissoc-in [:subscribers :mice])
                       (dissoc-in [:subscribers :layers]))
                {:react-key "app*"}))))
