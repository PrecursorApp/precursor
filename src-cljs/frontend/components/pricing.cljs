(ns frontend.components.pricing
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

(defn pricing [app owner]
  (reify
    om/IRender
    (render [_]
      (html
       [:div.page.pricing
        [:div.banner
         [:div.container
          [:h1 "Plans and Pricing"]
          [:p
           "Our pricing is flexible and scales with you. Add as many containers as you want for $50/month each."]]]
        [:div.container.content
         (om/build plans-component/plans app)
         (shared/customers-trust)
         (om/build plans-component/pricing-features app)
         plans-component/pricing-faq]]))))
