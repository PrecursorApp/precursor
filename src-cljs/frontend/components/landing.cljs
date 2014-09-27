(ns frontend.components.landing
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.string :as str]
            [frontend.async :refer [put!]]
            [frontend.components.common :as common]
            [frontend.components.crumbs :as crumbs]
            [frontend.components.shared :as shared]
            [frontend.env :as env]
            [frontend.state :as state]
            [frontend.stefon :as stefon]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.github :refer [auth-url]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [sablono.core :as html :refer-macros [html]])
  (:require-macros [frontend.utils :refer [defrender]]))

(def hero-graphic
  [:div.row-fluid.how
   [:div.span4
    [:img {:height 150
           :width 150
           :src (stefon/data-uri "/img/outer/home/octocat.png")}]
    [:h4 "Push your new code to GitHub"]]
   [:div.span4
    [:img {:height 150
           :width 150
           :src (stefon/data-uri "/img/outer/home/test-output.png")}]
    [:h4 "Your tests run on the CircleCI platform"]]
   [:div.span4
    [:img {:height 150
           :width 150
           :src (stefon/data-uri "/img/outer/home/deploy.png")}]
    [:h4 "Deploy green builds to your servers"]]])

(defn home-cta [ab-tests controls-ch]
  [:div.ctabox {:class (if first "line")}
   [:div
    [:p "Plans start at $19 per month. All plans include a free 14 day trial."]]
   (shared/home-button {:source "hero"} controls-ch)
   [:div
    [:p
     [:i "CircleCI keeps your code safe. "
      [:a {:href "/security" :title "Security"} "Learn how."]]]]])

(defn home-hero-unit [ab-tests controls-ch]
  [:div#hero
   [:div.container
    [:h1 "Ship better code, faster"]
      [:h3 "CircleCI gives web developers powerful Continuous Integration and Deployment with easy setup and maintenance."]
    [:div.how.row-fluid
       hero-graphic]
    [:div.row-fluid
     [:div.hero-unit-cta
      (home-cta ab-tests controls-ch)]]]])

(defn customer-image [customer-name image]
  [:div.big-company
   [:img {:title customer-name
          :src (stefon/data-uri image)}]])

(def home-customers
  [:section.customers
   [:div.container
    [:div.row.span12]
    [:div.center
     [:div.were-number-one
      [:h2
       "CircleCI is the #1 hosted continuous deployment provider. But don't take our word for it, read what our awesome customers have to say."]]]
    (shared/customers-trust)]
   [:div.container
    [:div.row
     [:div.customer.span4.well
      [:p
       "CircleCI has significantly improved our testing infrastructure. Tests finish faster. We add new projects rapidly and continuous integration happens from the get-go. "
       [:strong "I'm a huge fan."]]
      [:img {:src (stefon/data-uri "/img/outer/home/john-collison-2.png")
             :alt "John Collison"}]
      [:h4 "John Collison"]
      [:p
       "Founder at Stripe"]]
     [:div.customer.span4.well
      [:p
       "CircleCI lets us be more agile and ship product faster. "
       [:strong "We can focus on delivering value to our customers,"]
       " not maintaining Continuous Integration and Delivery infrastructure."]
      [:img {:src (stefon/asset-path "/img/outer/stories/john.jpg")
             :alt "Jon Crawford"}]
      [:h4 "John Duff"]
      [:p "Director of Engineering at Shopify"]]
     [:div.customer.span4.well
      [:p
       "CircleCI was super simple to set up and we started reaping the benefits immediately. It lets us ship code quickly and confidently. "
       [:strong "CircleCI's customer support is outstanding."]
       " We get quick, thorough answers to all our questions."]
      [:img {:src (stefon/data-uri "/img/outer/home/aaron-suggs.jpg")
             :alt "Aaron Suggs"}]
      [:h4 "Aaron Suggs"]
      [:p
       "Operations Engineer at Kickstarter"]]]]
   [:a.customer-story-link.center-text {:href "stories/shopify"}
    "Read how Shopify grew with Circle"]])

(def home-features
  [:div.benefits
   [:div.container
    [:div.row-fluid
     [:div.span12
      [:h2 "Features & Benefits of CircleCI"]
      [:h3
       "A professional continuous integration setup for your team today, tomorrow and beyond."]]]
    [:div.row-fluid
     [:div.clearfix.quick-setup.section.span4
      [:div.section-graphic [:i.fa.fa-magic]]
      [:div.section-content
       [:h3 "Quick Setup"]
       [:p
        [:strong "Set up your continuous integration in 20 seconds"]
        ", not two days. With one click CircleCI detects test settings for a wide range of web apps, and set them up automatically on our servers."]]]
     [:div.clearfix.fast-tests.section.span4
      [:div.section-graphic [:i.fa.fa-bolt]]
      [:div.section-content
       [:h3 "Fast Tests"]
       [:p
        "Your productivity relies on fast test results. CircleCI runs your tests "
        [:strong "faster than your Macbook Pro"]
        ", EC2, your local server, or any other service."]]]
     [:div.clearfix.deep-customization.section.span4
      [:div.section-graphic [:i.fa.fa-flask]]
      [:div.section-content
       [:h3 "Deep Customization"]
       [:p
        "Real applications often deviate slightly from standard configurations, so CircleCI does too. Our configuration is so flexible that it's easy to "
        [:strong "tweak almost anything"]
        " you need."]]]]
    [:div.row-fluid
     [:div.clearfix.debug-with-ease.section.span4
      [:div.section-graphic [:i.fa.fa-cog]]
      [:div.section-content
       [:h3 "Debug with Ease"]
       [:p
        "When your tests are broken, we help you get them fixed. We auto-detect errors, have great support, and even allow you to "
        [:strong "SSH into our machines"]
        " to test manually."]]]
     [:div.clearfix.section.smart-notifications.span4
      [:div.section-graphic [:i.fa.fa-bullhorn]]
      [:div.section-content
       [:h3 "Smart Notifications"]
       [:p
        "CircleCI intelligently notifies you via email, Hipchat, Campfire and more. You won't be flooded with useless notifications about other people's builds and passing tests, "
        [:strong "we only notify you when it matters."]]]]
     [:div.clearfix.incredible-support.section.span4
      [:div.section-graphic [:i.fa.fa-heart]]
      [:div.section-content
       [:h3 "Loving support"]
       [:p
        "We respond to support requests as soon as possible, every day. Most requests get a response responded to "
        [:strong "within an hour."]
        " No-one ever waits more than 12 hours for a response."]]]]
    [:div.row-fluid
     [:div.automatic-parallelization.clearfix.section.span4
      [:div.section-graphic [:i.fa.fa-fullscreen]]
      [:div.section-content
       [:h3 "Automatic Parallelization"]
       [:p
        "We can automatically parallelize your tests across multiple machines. "
        [:strong "With up to 16-way parallelization"]
        ", your test turn-around time can be massively reduced."]]]
     [:div.clearfix.continuous-deployment.section.span4
      [:div.section-graphic [:i.fa.fa-refresh]]
      [:div.section-content
       [:h3 "Continuous Deployment"]
       [:p
        [:strong "Get code to your customers faster"]
        ", as soon as the tests pass. CircleCI supports branch-specific deployments, SSH key management and supports any hosting environment using custom commands, auto-merging, and uploading packages."]]]
     [:div.clearfix.more-to-come.section.span4
      [:div.section-graphic [:i.fa.fa-lightbulb-o]]
      [:div.section-content
       [:h3 "More to come."]
       [:p
        "At CircleCI we are always listening to our customers for ideas and feedback. If there is a specific feature or configuration ability you need, we want to know."]]]]]])

(defn tech-tab [tab selected controls-ch]
  [:li {:class (when (= tab selected) "active")}
   [:a {:on-click #(put! controls-ch [:home-technology-tab-selected {:tab tab}])}
    (str/capitalize (name tab))]])

(defn tech-tab-content [tab template]
  [:div.active.tab-pane {:id (name tab)}
   (if (:img-url template)
     [:div.row-fluid
      [:div.span6 [:img {:src (utils/cdn-path (:img-url template))}]]
      [:div.span6
       (:blurb template)]]
     [:div.row-fluid
      [:div.offset2.span8
       [:p (:blurb template)]]])])

(defn home-technology [app owner]
  (reify
    om/IRender
    (render [_]
      (let [selected-tab (or (get-in app state/selected-home-technology-tab-path)
                             :languages)
            controls-ch (om/get-shared owner [:comms :controls])]
        (html
         [:section.technology
          [:div.container
           [:h2 "We support your stack"]
           [:div.tabbable
            [:div.row-fluid
             [:div.nav-tabs-container.span12
              [:ul#tech.nav.nav-tabs
               (for [tab [:languages :databases :queues :browsers :libraries :deployment :custom]]
                 (tech-tab tab selected-tab controls-ch))]]]
            [:div.tab-content
             (let [templates {:languages {:img-url "/img/outer/home/tech-languages.png"
                                          :blurb "CircleCI automatically infers how to run your Ruby, Node, Python and Java tests. You can customize any step, or set up your test steps manually for PHP or other languages."}
                              :databases {:img-url "/img/outer/home/tech-databases.png"
                                          :blurb "If you use any of the dozen most common databases, we have them pre-installed for you, set up to be trivial to use. Postgres and MySQL have their permissions set and are running, Redis, Mongo and Riak are running for you, and the other DBs are just a configuration switch away."}
                              :queues {:img-url "/img/outer/home/tech-queues.png"
                                       :blurb "If you need to test against queues, we have them installed on our boxes. We support RabbitMQ and Beanstalk, have Redis installed so you can use Resque, and can install anything else you need."}
                              :browsers {:img-url "/img/outer/home/tech-browsers.png"
                                         :blurb "We support continuous integration testing in your apps against a wide range of browsers. We have latest Chrome, Firefox and webkit installed using xvfb, as well as PhantomJS and CasperJS. Headless browser testing is completely supported, so you can test using Selenium, directly against PhantomJS, or using abstraction layers such as Capybara and Cucumber."}
                              :libraries {:blurb "We run a recent version Ubuntu and have installed all of the libraries you need for development. We have common libs like libxml2, uncommon ones like libblas, and downright rare libraries like libavahi-compat-libdnssd-dev. As new important libraries come out it's trivial for us to add them for you."}
                              :deployment {:img-url "/img/outer/home/tech-deployment.png"
                                           :blurb "Continuous Deployment means that you can deploy your fresh code to production fast and with no fear. Many of our customers deploy directly after a green push to master or another branch. We manage SSH keys and allow you to deploy any way you wish, whether directly to a PaaS, using Capistrano, Fabric, or arbitrary bash commands, or – for you autoscalers – by auto-merging to another branch, or packaging code up to S3."}
                              :custom {:blurb "Although we do our best to set up your tests in one click, occasionally developers have custom setups. Need to use npm in your PHP project? Using Haskell? Use a specific Ruby patchset? Do you depend on private repos? We have support for dozens of different ways to customize, and we make it trivial to customize basically anything. Custom language versions, environment variables, timeouts, packages, databases, commands, etc, are all trivial to set up."}}]
               (tech-tab-content selected-tab (get templates selected-tab)))]]]])))))

(defn home-get-started [controls-ch]
  [:div.get-started
   [:div.container
    [:div.row
     [:div.offset3.span6
      [:div.box
       [:h2 "Get Started"]
       [:hr]
       [:p.center
        [:strong "Set up your continuous integration in 20 seconds."]]
       [:ol
        [:li "Choose a GitHub repository."]
        [:li "Watch your tests run faster than ever."]
        [:li "Get your team into the flow."]]
       [:div.center
        [:div.main-cta
         [:div.ctabox
          [:a.btn.btn-action-orange.btn-jumbo
           {:href (auth-url)
            :on-click #(put! controls-ch [:track-external-link-clicked {:event "Auth GitHub"
                                                                        :properties {:source "get_started_section"}
                                                                        :path (auth-url)}])}
           "RUN YOUR TESTS"]]]]
       [:p.center
        [:i "CircleCI keeps your code safe. "
         [:a {:title "Privacy and Security", :href "/privacy"}
          "Learn how."]]]
       [:p.center "Plans start at " [:i "$19 per month"]
        [:br]
        "All plans include a " [:strong [:i "Free 14 Day Trial."]]]]]]]])

(defn home [app owner]
  (reify
    om/IRender
    (render [_]
      (let [ab-tests (:ab-tests app)
            controls-ch (om/get-shared owner [:comms :controls])]
        (html [:div.landing.page
               [:div.banner]
               [:div
                (home-hero-unit ab-tests controls-ch)
                home-customers
                (om/build home-technology app)
                home-features
                (home-get-started controls-ch)]])))))
