(ns frontend.components.plans
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [frontend.async :refer [put!]]
            [frontend.components.forms :as forms]
            [frontend.models.plan :as plan-model]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.github :as gh-utils]
            [inflections.core :refer (pluralize)]
            [om.core :as om :include-macros true]
            [clojure.string :as string]
            [goog.string :as gstring]
            [goog.string.format]
            [goog.style])
  (:require-macros [cljs.core.async.macros :as am :refer [go go-loop alt!]]
                   [dommy.macros :refer [node sel sel1]]
                   [frontend.utils :refer [html]]))


(defn pricing-cloud [app owner opts]
  (om/component
   (let [{:keys [containers plan-name recommended]} opts
         logged-in? (get-in app state/user-path)
         controls-ch (om/get-shared owner [:comms :controls])
         price (plan-model/cost plan-model/default-template-properties containers)]
     (html
      [:div.span3.plan {:class (when recommended "recommended")}
       [:div.plan-head
        (when recommended
          [:div.popular-ribbon [:span "Popular"]])
        [:h3 plan-name]]
       [:div.plan-body
        [:h2 (str "$" price "/mo")]
        [:ul
         [:li "Includes " (pluralize containers "container")]
         [:li "Additional containers $50/mo"]
         [:li "No other limits"]]]
       [:div.plan-foot
        (if logged-in?
          (forms/managed-button
           [:a {:data-loading-text "Paying...",
                :data-failed-text "Failed!",
                :data-success-text "Paid!",
                :on-click #(put! controls-ch [:new-plan-clicked {:containers containers
                                                                 :base-template-id (:id plan-model/default-template-properties)
                                                                 :price price
                                                                 :description (str "$" price "/month, includes " (pluralize containers "container"))}])}
            "Start Now"])

          [:a {:href (gh-utils/auth-url)
               :on-click #(put! controls-ch [:track-external-link-clicked {:path (gh-utils/auth-url)
                                                                           :event "Auth GitHub"
                                                                           :properties {:source "pricing-business"}}])}
           [:span "Start 14-day Free Trial"]])]]))))

(def pricing-enterprise
  [:div.plan.span3
   [:div.plan-head [:h3 "Enterprise"]]
   [:div.plan-body
    [:h2 [:span "Get a Quote"]]
    [:ul
     [:li "On-premises deployment"]
     [:li "GitHub Enterprise support"]
     [:li "Amazon VPC support"]]]
   [:div.plan-foot
    [:a {:href "/enterprise"}
     [:span "Learn More"]]]])

(defn plans [app owner]
  (om/component
   (html
    [:div.row-fluid.pricing-plans
     [:div.span12
      [:div.row-fluid
       (om/build pricing-cloud app {:opts {:containers 1 :plan-name "Solo"}})
       (om/build pricing-cloud app {:opts {:containers 2 :plan-name "Startup" :recommended true}})
       (om/build pricing-cloud app {:opts {:containers 6 :plan-name "Small Business"}})
       pricing-enterprise]]])))

(def features
  [{:headline "Unlimited private repositories",
    :detail "No repo limits, follow as many as you like."}
   {:headline "Unlimited parallelism",
    :detail "Subject to number of containers. For example, if you have 6 containers you can run up to 6x parallelism."},
   {:headline "Unlimited collaborators",
    :detail "Invite as many members of your team as you want to use CircleCI with you, we never limit your users."},
   {:headline "Unlimited builds",
    :detail "There's no limit to how often you can push.
You queue them up, we'll knock 'em down."},
   {:headline "Unlimited build time",
    :detail "Great testing means using Circle as much as possible. You don't want to weigh good testing against possible charges or monthly fees. So we have no limit, no per-hour cost, no overages. Just simple, predictable pricing."},
   {:headline "Phenomenal support",
    :detail "We respond to support requests immediately, every day (yes, on weekends and holidays). Most requests are responded to within the hour, 99% are responded to in 12 hours. We're also available for live chat support."},
   {:headline "Custom NDA",
    :detail "Naturally we never look at your code. We also have a great security policy, which gives us no rights whatsoever to your code. However, if you would prefer that we sign your custom NDA, we'd be happy to.",
    :enterprise true},
   {:headline "Service-level agreements",
    :detail "If you require a service contract we're happy to provide it.",
    :enterprise true},
   {:headline "Amazon VPC support",
    :detail "CircleCI allows you to run your builds on your own Amazon Virtual Private Cloud.",
    :enterprise true},
   {:headline "Data retention",
    :detail "Retain long-term build data and logs.",
    :enterprise true},
   {:headline "Scaleable and transparent pricing",
    :detail "Our pricing is container based, allowing you add as many containers as you need for $50/each. There are no charges for using machine time."},
   {:headline "Lightning fast hardware",
    :detail "We have the fastest test infrastructure around. Running on Sandybridge Xeons, with tons of RAM, and hardcore IO tuning for your databases, you won't find a faster test service."},
   {:headline "Incredible test optimizations",
    :detail "We've tuned every part of our test framework for incredibly fast tests, from our tuned kernel settings, to our caches, to our auto-scaling architecture, you won't wait for anything."},
   {:headline "Continuous Deployment",
    :detail "If your tests work, deploy straight to staging, production, or a QA server.\nDeploy anywhere, including Heroku, DotCloud, EngineYard, etc, or using Capistrano, Fabric or custom commands."},
   {:headline "One-click setup",
    :detail "You can literally set up your tests in 20 seconds. That's less than the time it takes to find the Jenkins download link, and a lot less than the time to requisition a server to host it on. We support every test suite, database, library, and language you need (Linux stacks only for now)"},
   {:headline "Personalized notifications",
    :detail "You don't care what John did in his feature branch, but you care what gets pushed to master. Who cares if the tests pass yet again, you only want notifications when they fail. Circle intelligently notifies you about the tests you care about, and does it over email, Hipchat, Campfire, FlowDock, IRC and webhooks."},
   {:headline "Debug with ease",
    :detail "When your tests are broken, we help you get them fixed. We automatically warn you about common mistakes, and document how to fix them. We provide all the information you need to reproduce an error locally. And if you still can't reproduce it, you can SSH into our VMs to debug it yourself."}])

(defn feature [feature owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (utils/popover (str "#" (:popover-uuid feature))
                     {:html true
                      :trigger "hover"
                      :delay 0
                      :animation false
                      :placement "right"
                      :template "<div class=\"popover billing-popover\"><div class=\"popover-inner\"><h3 class=\"popover-title\"></h3><div class=\"popover-content\"></div></div></div>"}))
    om/IRender
    (render [_]
      (html
       [:tr
        [:td.span4
         [:span {:title (:headline feature)
                 :data-content (:detail feature)
                 :id (:popover-uuid feature)}
          [:strong (:headline feature)]
          [:i.fa.fa-info-circle]]]
        (for [plan ["Solo" "Startup" "Small Business" "Enterprise"]]
          [:td.span2
           (when (or (not (:enterprise feature))
                     (= "Enterprise" plan))
             [:i.fa.fa-check])])]))))

(defn pricing-features [app owner]
  (reify
    om/IRender
    (render [_]
      (html
       [:div.features.row
        [:h2 "Features"]
        [:table.table
         [:thead
          [:tr
           [:th.span4]
           [:th.span2 "Solo"]
           [:th.span2 "Startup"]
           [:th.span2 "Small Business"]
           [:th.span2 "Enterprise"]]]
         [:tbody
          (om/build-all feature (map #(assoc % :popover-uuid (utils/uuid)) features))]]]))))

(def pricing-faq
  [:div.faq.row
   [:h2 "Billing FAQ"]
   [:section
    [:h4 "Who is included in my plan?"]
    [:p "Your plan covers any number of GitHub organization or personal accounts. All pushes to those organizations will be tested, and will use your available containers."]]

   [:section
    [:h4 "How do containers work?"]
    [:p "Every time you push to GitHub, we checkout your code and run your build inside of a container. If you don't have enough free containers available, then your pushes queue up until other builds finish."]
    [:p "You can buy containers at $50 each to run multiple builds simultaneously."]
    [:p "You can use our parallelism to speed up your test suite. We automatically split your tests across multiple containers, finishing your build in a fraction of the time."]
    [:p "For example, with 4 containers you can run 4 simultaneous builds at 1x parallelism or 2 simultaneous builds in half the time at 2x parallelism."]]

   [:section
    [:h4 "How many containers do I need?"]
    [:p "This very much depends on the team size, frequency of pushing, and the length of your test suite. Small teams with short tests who push frequently should only need one. A team of 20 full-time developers, with a 15 minute test suite, who push 5 times a day each, will probably need 6-8 containers."]]

   [:section
    [:h4 "Are there any extra charges?"]
    [:p "Circle has no extra charges in any of our standard plans. You have unlimited testing time, unlimited builds, unlimited anything which is not enumerated here. We don't believe that we should incentivise you to test less."]]

   [:section
    [:h4 "Really? Unlimited?"]
    [:p "For practical purposes, our plans are unlimited. You won't be cut off for pushing too frequently, having a long test suite, or anything like that. However, like all services, we reserve the right to restrict abusive behaviour. If you're pushing a 10 hour test suite every minute, we won't feel bad about cutting you off."]]

   [:section
    [:h4 "Why should I pay for this instead of using Jenkins?"]
    [:p "Maintaining a CI server takes considerable time - we estimate about 8% of a team's time is spent on maintaining their build infrastructure (of course, much more is wasted if you don't have build infrastructure, but that's a separate question). Circle allows you to buy back this productivity, and let you focus on building your product."]
    [:p "With Circle, you'll never have to buy machines, install plugins, refactor the test suite for speed, or spend any time on your test infrastructure. With Circle, you don't need to worry about your tools - just focus on the product and leave the tools to us."]]

   [:section
    [:h4 "How does parallelism work? Isn't that error-prone?"]
    [:p "Most parallelism libraries are aimed at running on single machines, and so can be error-prone. We have found that it is hard to make parallelism work when the parallel tests share the same address-space, heap, run-time, process, database or even the same file-system."]
    [:p "Circle instead splits the tests and runs them in separate containers. Each container is completely separate, with no shared code, no shared databases, no shared anything. Our customers have found it to be very reliable."]]
   [:section
    [:h4 "Why use containers for pricing?"]
    [:p "After talking to our users, we based our pricing on two important principles: charge in a fair way, and align our incentives with yours. We did some experiments with per-user pricing, but eventually decided that the fairest way is to let people pay for the resources they consume."]
    [:p "It's fair because it scales with the amount of work your team generates. At the same time, it's affordable for teams of all size."]
    [:p "Most importantly, it allows us to maximize your productivity, without limits on builds or testing time. Circle works to make you as productive as possible, and we won't let our pricing get in the way."]]

   [:section
    [:h4 "My team needs something not listed here"]
    [:p "Contact us at "
     [:a {:href "mailto:enterprise@circleci.com"} "enterprise@circleci.com"]
     " to discuss the benefits of an enterprise plan."]]])
