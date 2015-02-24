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

(defn request-access [app owner]
  (reify
    om/IDisplayName (display-name [_] "Request Access")
    om/IRender
    (render [_]
      (let [{:keys [cast! handlers]} (om/get-shared owner)]
        (html

          ; [:div.request-access
          ;  [:div.request-access-content
          ;   [:div.request-access-info
          ;    [:h2.request-access-heading
          ;     "We're excited to show you our team features."]
          ;    [:p.request-access-copy
          ;     "To activate your early access, please sign in and let us know about the following info.
          ;     We'll send you an email confirmation once your account has been granted full access."]
          ;    [:div.calls-to-action
          ;      (common/google-login)]]
          ;   [:div.request-access-form
          ;    [:div.adaptive-placeholder {:tab-index "2" :data-before "What's your company's name?" :data-after "Company Name" :content-editable ""}]
          ;    [:div.adaptive-placeholder {:tab-index "3" :data-before "How many employees are there?" :data-after "Employee Count" :content-editable ""}]
          ;    [:div.adaptive-placeholder {:tab-index "4" :data-before "How will you use Precursor?" :data-after "Use Case" :content-editable ""}]
          ;    [:button.request-access-button {:tab-index "5"} "Request early access."]]]]

          [:div.pricing
           [:div.content
            [:div.pricing-blocks
             [:div.pricing-block
              [:div.pricing-head
               [:h2.pricing-heading.content-copy "Solo"]]
              [:div.pricing-body
               [:h4.content-copy "$10/mo"]
               [:p.pricing-copy.content-copy "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Nunc sollicitudin, quam id eleifend pretium, quam arcu sodales purus."]]
              [:div.pricing-foot
               [:button.pricing-button "Request early access."]]]
             [:div.pricing-divider
              [:div.pricing-divider-line]]
             [:div.pricing-block
              [:div.pricing-head
               [:h2.pricing-heading.content-copy "Team"]]
              [:div.pricing-body
               [:h4.content-copy "$10/mo/user"]
               [:p.pricing-copy.content-copy "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Nunc sollicitudin, quam id eleifend pretium, quam arcu sodales purus."]]
              [:div.pricing-foot
               [:button.pricing-button "Request early access."]]]
             [:div.pricing-divider
              [:div.pricing-divider-line]]
             [:div.pricing-block
              [:div.pricing-head
               [:h2.pricing-heading.content-copy "Enterprise"]]
              [:div.pricing-body
               [:h4.content-copy "Contact us."]
               [:p.pricing-copy.content-copy "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Nunc sollicitudin, quam id eleifend pretium, quam arcu sodales purus."]]
              [:div.pricing-foot
               [:button.pricing-button "Contact us."]]]

             ]]]

          )))))

(defn app* [app owner]
  (reify
    om/IDisplayName (display-name [_] "App")
    om/IRender
    (render [_]
      (let [{:keys [cast! handlers]} (om/get-shared owner)
            chat-opened? (get-in app state/chat-opened-path)
            right-click-learned? (get-in app state/right-click-learned-path)]
        (html [:div.inner {:on-click (when (overlay-visible? app)
                                       #(cast! :overlay-closed))
                           :class (when (empty? (:frontend-id-state app))
                                    "loading")}
               [:style "#om-app:active{cursor:auto}"]
               (om/build canvas/canvas app)
               (om/build chat/chat app)
               [:div.mouse-stats
                {:data-mouse (if (:mouse app)
                               (pr-str (select-keys (:mouse app) [:x :y :rx :ry]))
                               "{:x 0, :y 0, :rx 0, :ry 0}")}]])))))

(defn app [app owner]
  (reify
    om/IRender
    (render [_]
      (if (:navigation-point app)
        (dom/div #js {:id "app" :className "app"}
          (om/build request-access app)
          (when (:show-landing? app)
            (om/build landing/landing app))

          (when (and (= :document (:navigation-point app))
                     (not (:cust app)))
            (om/build drawing/signup-button {:db/id (:document/id app)}))

          (when (overlay-visible? app)
            (om/build overlay/overlay app))
          (om/build app* app)
          (om/build hud/hud app))
        (html [:div#app])))))
