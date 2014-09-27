(ns frontend.components.enterprise
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.string :as str]
            [frontend.async :refer [put!]]
            [frontend.components.common :as common]
            [frontend.components.plans :as plans-component]
            [frontend.components.shared :as shared]
            [frontend.state :as state]
            [frontend.stefon :as stefon]
            [frontend.utils :as utils :include-macros true]
            [om.core :as om :include-macros true])
  (:require-macros [frontend.utils :refer [defrender html]]))

(def server-image
  [:svg#enterprise-server
   {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 100 100"}
   [:path {:d "M78.627,64.127l0.015,5.798c0,6.915-13.247,11.622-29.057,11.622S21.358,76.84,21.358,69.925l0.015-5.798 c0.311,0.273,8.666,7.458,28.212,7.458C69.165,71.585,78.315,64.376,78.627,64.127z M21.392,47.539v9.933 c0,0,10.759,7.472,28.226,7.472c17.4,0,29.023-7.472,29.023-7.472v-9.962c0,0-9.132,7.472-29.057,7.472 C30.357,54.981,21.956,48.025,21.392,47.539z M78.642,30.906v9.132c0,0-6.642,8.301-29.057,8.301 c-21.585,0-28.227-8.301-28.227-8.301v-9.132c0,0,0-0.627,0-0.945c0-6.427,12.417-11.508,28.227-11.508s29.057,5.081,29.057,11.508 C78.642,30.279,78.642,30.906,78.642,30.906z M68.679,29.246c0-3.667-8.548-6.642-19.094-6.642S30.49,25.578,30.49,29.246 s8.549,6.641,19.095,6.641S68.679,32.913,68.679,29.246z"
           :fill "#004B64"}]])

(def cloud-image
  [:svg#enterprise-cloud
   {:viewBox "0 0 100 100" :xmlns "http://www.w3.org/2000/svg"}
   [:path {:d "M86.207,44.39c-2.895-7.773-10.316-13.305-19.02-13.305c-2.002,0-3.938,0.293-5.765,0.839 c-4.96-8.086-13.818-13.471-23.923-13.471c-15.326,0-27.79,12.393-28.119,27.804C3.771,49.537,0,55.671,0,62.67 c0,10.468,8.398,18.877,18.75,18.877h49.219H81.25c10.354,0,18.75-8.433,18.75-18.877C100,53.938,94.155,46.586,86.207,44.39z"
           :fill "#004B64"}]])

(def control-image
  [:svg#enterprise-server
   {:viewBox "0 0 100 100"}
   [:path {:d "M50,0C22.387,0,0,22.387,0,50c0,27.615,22.386,50,50,50s50-22.385,50-50C100,22.387,77.614,0,50,0z",
           :fill "#004B64"}]
   [:path
    {:d "M73.938,39.078H69.25v-6.281c0-8.594-6.25-17.938-18.75-17.938 s-18.75,9.344-18.75,17.938v6.281h-4.688c-1.408,0-2.344,0.937-2.344,2.344v35.125c0,1.407,0.936,2.375,2.344,2.375h46.875 c1.408,0,2.344-0.968,2.344-2.375V41.422C76.281,40.015,75.346,39.078,73.938,39.078z M44.192,69.516l3.65-10.043 c-1.675-0.942-2.811-2.745-2.811-4.801c0-3.021,2.448-5.438,5.469-5.438s5.469,2.417,5.469,5.438c0,2.057-1.137,3.86-2.814,4.804 l3.596,10.04H44.192z M63.047,39.078H37.973v-6.281c0-4.963,3.152-11.688,12.527-11.688s12.547,6.725,12.547,11.688V39.078z",
     :fill "#FFFFFF",
     :clip-rule "evenodd",
     :fill-rule "evenodd"}]])

(def support-image
  [:svg#enterprise-server
   {:viewBox "0 0 100 100"}
   [:path {:d "M50,0C22.387,0,0,22.387,0,50c0,27.615,22.386,50,50,50s50-22.385,50-50C100,22.387,77.614,0,50,0z",
           :fill "#004B64"}]
   [:path {:d "M66.062,44.482c0,8.946-9.759,16.199-21.799,16.199c-2.798,0-5.47-0.397-7.928-1.11 c-4.078,3.676-8.998,4.209-10.202,4.145c-1.479-0.079-2.085-0.854-0.741-1.708c0.916-0.582,2.967-2.755,4.645-5.257 c-4.634-2.972-7.572-7.363-7.572-12.268c0-8.946,9.759-16.198,21.799-16.198C56.304,28.284,66.062,35.536,66.062,44.482z M74.62,69.973c-0.965-0.574-3.191-2.818-4.972-5.384c4.776-2.963,7.816-7.407,7.816-12.38c-0.001-4.996-3.071-9.462-7.893-12.425 c0.496,1.491,0.766,3.048,0.766,4.651c0,10.699-11.729,19.371-26.196,19.371c-1.375,0-2.725-0.08-4.043-0.23 c3.959,2.957,9.452,4.788,15.522,4.788c2.627,0,5.145-0.345,7.478-0.973c4.279,3.985,9.578,4.416,10.83,4.312 C75.403,71.581,75.987,70.789,74.62,69.973z",
           :fill "#FFFFFF"}]])

(def integration-image
  [:svg#enterprise-server
   {:viewBox "0 0 100 100"}
   [:path {:d "M50,0C22.387,0,0,22.387,0,50c0,27.615,22.386,50,50,50s50-22.385,50-50C100,22.387,77.614,0,50,0z",
           :fill "#004B64"}]
   [:path {:d "M73.602,43.571l-8.816,6.825c0.03,0.386,0.059,0.772,0.059,1.167c0,8.199-6.646,14.844-14.843,14.844 s-14.844-6.645-14.844-14.844c0-8.198,6.646-14.843,14.844-14.843v9.375L69.531,31.25L50,17.188v9.629c-13.807,0-25,11.193-25,25 c0,13.807,11.193,25,25,25c13.808,0,25-11.193,25-25C75,48.926,74.504,46.153,73.602,43.571z",
           :fill "#FFFFFF"}]])

(defn modal [app owner]
  (reify
    om/IRender
    (render [_]
      (html
       [:div#enterpriseModal.fade.hide.modal
        [:div.modal-body
         [:h4
          "Contact us to learn more about enterprise Continous Delivery"]
         [:hr]
         (om/build shared/contact-form app {:opts {:enterprise? true}})]]))))

(defn enterprise [app owner]
  (reify
    om/IRender
    (render [_]
      (let [controls-ch (om/get-shared owner [:comms :controls])]
        (html
         [:div#enterprise
          [:div.enterprise-hero
           [:section.enterprise-hero-wrapper
            [:article.enterprise-hero-title
             [:h1 "Ship code at the speed of business."]
             [:h3 "Powerful, continuous deployment, on-premises or in the cloud."]]
            [:article.enterprise-hero-units
             [:div.enterprise-hero-unit
              server-image
              [:h2 "CircleCI on-premises"]
              [:p "Install CircleCI behind your firewall or in a private cloud for security that you control."]]
             [:div.enterprise-hero-unit
              cloud-image
              [:h2 "CircleCI in the cloud"]
              [:p "All the same great features as CircleCI's hosted offering along with SLA, role-based access control, flexible payments options and more."]]]]]
          [:div.enterprise-features
           [:section.enterprise-features-wrapper
            [:div.enterprise-feature
             [:article
              [:h4 "Maintain complete control"]
              [:p "With on-premises and private cloud options, role-based access control, and enterprise grade security you can maintain complete control of your source code and deployment process."]
              [:a {:on-click #(put! controls-ch [:enterprise-learn-more-clicked {:source "control"}])}
               "Learn More"]]
             control-image]
            [:div.enterprise-feature
             support-image
             [:article
              [:h4 "Enterprise support"]
              [:p
               "Your success is our number one priority which is why we offer 24/7 support and multiple deployment options with service-level agreements."]
              [:a {:on-click #(put! controls-ch [:enterprise-learn-more-clicked {:source "support"}])}
               "Learn More"]]]
            [:div.enterprise-feature
             [:article
              [:h4 "Seamless integration"]
              [:p
               "We integrate with your current workflow via Github Enterprise, Amazon VPC, and more."]
              [:a
               {:on-click #(put! controls-ch [:enterprise-learn-more-clicked {:source "integration"}])}
               "Learn More"]]
             integration-image]]]
          [:div.enterprise-cta
           [:section.enterprise-cta-wrapper
            [:div.enterprise-cta-contact
             [:button
              {:on-click #(put! controls-ch [:enterprise-learn-more-clicked {:source "hero"}])}
              "Contact Us"]]
            [:div.enterprise-cta-trust
             (shared/customers-trust)]
            (om/build modal app)]]])))))
