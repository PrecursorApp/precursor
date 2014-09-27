(ns frontend.components.stories
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.string :as str]
            [frontend.async :refer [put!]]
            [frontend.analytics.marketo :as marketo]
            [frontend.components.common :as common]
            [frontend.components.plans :as plans-component]
            [frontend.components.shared :as shared]
            [frontend.state :as state]
            [frontend.stefon :as stefon]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.github :as gh-utils]
            [om.core :as om :include-macros true])
  (:require-macros [frontend.utils :refer [defrender html]]
                   [cljs.core.async.macros :as am :refer [go go-loop alt!]]))

(def shopify-logo
  [:svg.shopify-logo
   {:viewBox "0 0 225 75"}
   [:path.logomark {:d "M66.5,50.5c1.4,0.7,3.9,1.7,6.2,1.7c2.1,0,3.3-1.2,3.3-2.6c0-1.4-0.8-2.3-3.1-3.6c-2.9-1.7-5-4-5-7 c0-5.3,4.6-9.1,11.2-9.1c2.9,0,5.2,0.6,6.4,1.3l-1.8,5.4c-1-0.5-2.8-1-4.7-1c-2.1,0-3.5,1-3.5,2.5c0,1.2,1,2.1,2.9,3.1 c3,1.7,5.4,4,5.4,7.3c0,6.1-4.9,9.5-11.7,9.4c-3.1-0.1-6.1-0.9-7.5-1.9L66.5,50.5z M85.3,57.3l7.4-38.8h7.6l-2.9,15.2l0.1,0.1 c2-2.4,4.7-4,8-4c4,0,6.2,2.6,6.2,6.9c0,1.3-0.2,3.3-0.6,5l-3,15.7h-7.6l2.9-15.2c0.2-1,0.3-2.3,0.3-3.4c0-1.7-0.7-2.8-2.4-2.8 c-2.4,0-5,3.1-6,8.1l-2.5,13.3H85.3z M139.2,40.4c0,9.4-6.1,17.4-15,17.4c-6.8,0-10.5-4.7-10.5-10.6c0-9.2,6.1-17.4,15.2-17.4 C136,29.8,139.2,34.9,139.2,40.4z M121.5,47c0,2.8,1.1,5,3.7,5c4,0,6.3-7.2,6.3-11.8c0-2.3-0.9-4.6-3.6-4.6 C123.8,35.6,121.5,42.7,121.5,47z M137.4,68.1l5.3-27.8c0.6-3.1,1.2-7.3,1.5-10.1h6.7l-0.4,4.1h0.1c2-2.9,5-4.5,8-4.5 c5.6,0,7.8,4.6,7.8,9.7c0,9.2-5.8,18.4-14.8,18.4c-1.9,0-3.6-0.4-4.5-1h-0.2L145,68.1H137.4z M148.3,51c0.8,0.7,1.8,1.1,3.1,1.1 c4.2,0,7.2-7,7.2-11.9c0-2-0.7-4.2-3-4.2c-2.6,0-5.1,3.1-6,7.9L148.3,51z M166.7,57.3l5.1-26.9h7.7l-5.2,26.9H166.7z M176.6,27.1 c-2.1,0-3.6-1.5-3.6-3.8c0-2.5,1.9-4.4,4.3-4.4c2.3,0,3.8,1.6,3.8,3.8c-0.1,2.8-2.1,4.4-4.5,4.4H176.6z M180.9,57.3l4-21.2h-3.5 l1.1-5.7h3.5l0.2-1.3c0.6-3.2,1.8-6.4,4.4-8.6c2-1.8,4.7-2.5,7.4-2.5c1.9,0,3.2,0.3,4.1,0.7l-1.5,5.9c-0.7-0.2-1.4-0.4-2.4-0.4 c-2.5,0-4.1,2.3-4.5,4.9l-0.3,1.3h5.3l-1,5.7h-5.2l-4,21.2H180.9z M208.2,30.4l1.2,12.1c0.3,2.7,0.6,4.6,0.7,6.4h0.1 c0.6-1.8,1.2-3.5,2.3-6.4l4.6-12.1h7.9l-9.3,19.9c-3.3,6.8-6.5,11.8-10,15.1c-2.7,2.5-5.9,3.8-7.4,4.1l-2.1-6.4 c1.3-0.4,2.9-1.1,4.3-2.1c1.8-1.2,3.2-2.9,4.1-4.6c0.2-0.4,0.3-0.7,0.2-1.3l-4.6-24.7H208.2z",
                    :fill "#fff"}]
   [:path.logotype {:d "M37.5,13l-1.9,0.6c-0.8-2.5-2-4.2-3.4-5.2c-1.1-0.7-2.2-1.1-3.5-1c-0.3-0.3-0.5-0.6-0.9-0.9 c-1.4-1.1-3.2-1.3-5.3-0.5c-6.4,2.3-9.1,10.6-10.1,14.8l-5.6,1.7c0,0-1.3,0.4-1.6,0.7c-0.3,0.4-0.4,1.5-0.4,1.5L0,61.3L35.6,68 l2.7-55C37.9,12.9,37.5,13,37.5,13z M28.4,15.8l-6.2,1.9c0.8-3.2,2.4-6.5,5.4-7.8C28.3,11.6,28.4,13.8,28.4,15.8z M23.2,8 c1.3-0.5,2.3-0.5,3.1,0.1c-4,1.8-5.8,6.5-6.6,10.4L14.8,20C15.9,16,18.4,9.8,23.2,8z M26.7,34.9c-0.3-0.1-0.6-0.3-1-0.4 c-0.4-0.1-0.8-0.3-1.2-0.4C24,34,23.5,33.9,23,33.9c-0.5-0.1-1-0.1-1.6,0c-0.5,0-1,0.1-1.4,0.3c-0.4,0.1-0.7,0.3-1,0.6 c-0.3,0.2-0.5,0.5-0.7,0.9c-0.2,0.3-0.2,0.7-0.3,1.1c0,0.3,0,0.6,0.1,0.9c0.1,0.3,0.3,0.6,0.5,0.8c0.2,0.3,0.5,0.6,0.9,0.8 c0.4,0.3,0.8,0.6,1.3,0.9c0.7,0.4,1.4,0.9,2,1.5c0.7,0.6,1.3,1.2,1.9,2c0.6,0.7,1,1.6,1.3,2.5c0.3,0.9,0.5,2,0.4,3.1 c-0.1,1.9-0.5,3.5-1.2,4.8c-0.7,1.3-1.6,2.4-2.7,3.1c-1.1,0.7-2.3,1.2-3.7,1.4c-1.3,0.2-2.8,0.1-4.2-0.2c0,0,0,0,0,0c0,0,0,0,0,0 c0,0,0,0,0,0c0,0,0,0,0,0c-0.7-0.2-1.4-0.4-2-0.6c-0.6-0.2-1.2-0.5-1.7-0.8c-0.5-0.3-1-0.6-1.4-1c-0.4-0.3-0.7-0.7-1-1l1.6-5.4 c0.3,0.2,0.6,0.5,1,0.8c0.4,0.3,0.8,0.6,1.3,0.8c0.5,0.3,1,0.5,1.5,0.7c0.5,0.2,1.1,0.4,1.6,0.4c0.5,0.1,0.9,0.1,1.3,0 c0.4-0.1,0.7-0.2,0.9-0.4c0.3-0.2,0.5-0.5,0.6-0.8c0.1-0.3,0.2-0.7,0.2-1c0-0.4,0-0.7-0.1-1.1c-0.1-0.3-0.2-0.7-0.5-1 c-0.2-0.3-0.5-0.7-0.9-1c-0.4-0.3-0.8-0.7-1.3-1.1c-0.6-0.5-1.2-1-1.7-1.5c-0.5-0.5-1-1.1-1.3-1.8c-0.4-0.6-0.7-1.3-0.9-2 c-0.2-0.7-0.3-1.5-0.2-2.4c0.1-1.4,0.4-2.8,0.8-4c0.5-1.2,1.2-2.3,2.1-3.2c0.9-1,2-1.8,3.2-2.4c1.3-0.6,2.7-1.1,4.4-1.3 c0.8-0.1,1.5-0.1,2.2-0.1c0.7,0,1.3,0,1.9,0.1c0.6,0.1,1.2,0.2,1.7,0.3c0.5,0.1,0.9,0.3,1.3,0.5L26.7,34.9z M30.5,15.1 c0-0.2,0-0.5,0-0.7c-0.1-1.9-0.3-3.5-0.8-4.9c0.5,0,0.9,0.2,1.3,0.5c1.1,0.8,1.9,2.4,2.5,4.2L30.5,15.1z M55.7,63.8L36.5,68l2.7-55 c0.2,0,0.3,0.1,0.5,0.2l3.7,3.7l5,0.4c0,0,0.2,0,0.3,0.1c0.2,0.1,0.2,0.4,0.2,0.4L55.7,63.8z",
                    :fill "#fff"}]])

(defn cta-form [app owner opts]
  (reify
    om/IInitState
    (init-state [_]
      {:first-name ""
       :last-name ""
       :company ""
       :email ""
       :notice nil
       :loading? false})
    om/IRenderState
    (render-state [_ {:keys [first-name last-name company email notice loading?]}]
      (let [controls-ch (om/get-shared owner [:comms :controls])
            split? (:split? opts)
            clear-notice! #(om/set-state! owner [:notice] nil)
            clear-form! (fn [& [notice]]
                          (om/update-state! owner (fn [s]
                                                    (merge s
                                                           (when notice {:notice notice})
                                                           {:first-name ""
                                                            :last-name ""
                                                            :company ""
                                                            :email ""
                                                            :loading? false}))))]
        (html
         [:form
          [:div {:class (when split? "adaptive-inline")}
           [:div
            [:input {:name "FirstName",
                     :required true
                     :value first-name
                     :on-change #(do (clear-notice!) (om/set-state! owner [:first-name] (.. % -target -value)))
                     :type "text"}]
            [:label {:alt "First Name", :placeholder "First Name"}]]
           [:div
            [:input
             {:name "LastName",
              :required true
              :data-bind last-name
              :on-change #(do (clear-notice!) (om/set-state! owner [:last-name] (.. % -target -value)))
              :type "text"}]
            [:label {:alt "Last Name", :placeholder "Last Name"}]]]
          [:input
           {:name "Company",
            :required true
            :value company
            :on-change #(do (clear-notice!) (om/set-state! owner [:company] (.. % -target -value)))
            :type "text"}]
          [:label {:alt "Company", :placeholder "Company"}]
          [:input
           {:name "Email",
            :required true
            :value email
            :on-change #(do (clear-notice!) (om/set-state! owner [:email] (.. % -target -value)))
            :type "text"}]
          [:label {:alt "Email (required)", :placeholder "Email"}]
          [:div.notice
           (when notice
             [:span {:class (:type notice)} (:message notice)])]
          [:div.submit
           [:input
            {:value (if loading? "Submitting.." "Submit Request"),
             :class (when loading? "disabled")
             :on-click #(do (if (or (empty? email) (not (utils/valid-email? email)))
                              (om/set-state! owner [:notice] {:type "error"
                                                              :message "Please enter a valid email address."})
                              (do
                                (om/set-state! owner [:loading?] true)
                                (go (let [resp (<! (marketo/submit-munchkin-form 1022 {:FirstName first-name
                                                                                       :Email email
                                                                                       :Company company}))]
                                      (if-not (= :success (:status resp))
                                        (om/update-state! owner (fn [s]
                                                                  (merge s {:loading? false
                                                                            :notice {:type "error"
                                                                                     :message "Sorry! There was an error submitting the form. Please try again or email sayhi@circleci.com."}})))
                                        (clear-form! {:type "success"
                                                      :message "Thanks! We will be in touch soon."}))))))
                            false)
             :type "submit"}]]])))))

(defn cta-split [app owner]
  (reify
    om/IRender
    (render [_]
      (let [controls-ch (om/get-shared owner [:comms :controls])]
        (html
         [:div.container
          [:div.section (shared/customers-trust)]
          [:div.cta-contianer
           [:a.bold-btn.btn.btn-primary
            {:href (gh-utils/auth-url)
             :on-click #(put! controls-ch [:track-external-link-clicked {:event "Auth GitHub"
                                                                         :properties {:source "shopify story"}
                                                                         :path (gh-utils/auth-url)}])}
            [:i.fa.fa-github-alt]
            " Sign up for a "
            [:strong.white "14-day Free Trial"]]
           [:div.seperator [:div.line [:div "or"]]]
           [:div
            [:h3.center-text "Request A Demo"]
            (om/build cta-form app {:opts {:split? true}})]]])))))

(defn cta-simple [app owner]
  (reify
    om/IRender
    (render [_]
      (let [controls-ch (om/get-shared owner [:comms :controls])]
        (html
         [:div.stories-cta
          [:h3 "Request a Free Demo"]
          [:a {:href (gh-utils/auth-url)
               :on-click #(put! controls-ch [:track-external-link-clicked {:event "Auth GitHub"
                                                                           :properties {:source "shopify story"}
                                                                           :path (gh-utils/auth-url)}])}
           [:span "Or try Circle for free!"]]
          [:div (om/build cta-form app {:opts {:split? false}})]])))))

(defn shopify [app owner]
  (reify
    om/IRender
    (render [_]
      (let [controls-ch (om/get-shared owner [:comms :controls])
            ab-tests (:ab-tests app)]
        (html
         [:div.page.shopify.stories
          [:div.stories-head
           shopify-logo
           [:blockquote
            {:cite "http://www.shopify.com"}
            [:p "We were able to rapidly grow our team and code base without fear of outgrowing CircleCI."]
            [:cite [:img {:src (stefon/asset-path "/img/outer/stories/john.jpg")}]
             [:ul
              [:li "John Duff"]
              [:li "Director of Engineering, Shopify"]]]]]
          [:div.stories-body
           [:section
            [:div.stories-stats
             [:ul
              [:li [:span "Developers"] [:span "130"]]
              [:li [:span "Funding"] [:span "122m"]]
              [:li [:span "Technology"] [:span "Ruby"]]
              [:li [:span "Past Setup"] [:span "Internal"]]]]
            [:h2 "Background"]
            [:p
             "Shopify has a simple goal: to make commerce better. They do this by making it easy for over 100,000 online and brick and mortar retailers to accept payments. They've experienced tremendous growth over the last several years and in order to serve their growing customer base they've had to double their engineering team from 60 to 130 in the last year alone. In addition to the usual growth challenges, they faced the problem of maintaining their test-driven and continuous deployment culture while keeping productivity high."]
            [:p
             "Shopify has always taken Continuous Integration (CI) and Continuous Deployment (CD) seriously and built an in-house tool early on to make both practices an integrated part of their workflow. As the team grew, this in-house tool required more and more work to maintain and the self-hosted server was struggling to handle the load."]
            [:p
             "Eventually the Shopify team was dedicating the equivalent of two full-time engineers to maintaining their CI tool, and even then it was not performing up to their needs. Custom configuration was required to add a new repository, it was difficult to integrate with other tools, and the test suite took too long. In the spring of 2013 freeing up developers to focus on the core product, creating a more streamlined and flexible developer workflow, and getting new products to market quickly were all high priorities for John Duff, Shopify's Director of Engineering. Much of this, he decided, could be accomplished with a better CI solution."]]
           [:section
            [:h2 "Analysis"]
            [:p
             "In looking for a CI solution, Shopify needed something that was dependable, affordable, and would meet three core product criteria."]
            [:div.stories-buckets
             [:article
              [:i.fa.fa-dashboard]
              [:h3 [:span "Powerful Scalability"]]
              [:p
               "Shopify was undergoing rapid growth and they needed a solution that they would not outgrow."]]
             [:article
              [:i.fa.fa-wrench]
              [:h3 [:span "Robust API"]]
              [:p
               "Shopify required a robust API that would allow integration into their existing workflow and tools."]]
             [:article
              [:i.fa.fa-cogs]
              [:h3 [:span "Easy Customization"]]
              [:p
               "Shopify needed the ability to easily configure, customize, and debug build their machines."]]]
            [:p
             "Several of the developers at Shopify had used CircleCI before and recommended it as potentially meeting all of these needs. In order to try out CircleCI several developers signed up for a 14-day free trial using their GitHub accounts and followed a couple of their Shopify repositories from within the CircleCI app. CircleCI then inferred their test environment based on existing code and their tests began to run automatically. The developers were able to get quick clarification on a few setup details via CircleCI's live chat support room, and within a few minutes they were convinced that CircleCI would meet and surpass their needs."]
            [:p
             "After using the product for a few days there were several features that set CircleCI apart from the others; easy scalability of both build containers and parallelism, a well documented REST API, and extensive customization and configurability options including SSH access to the build machines. This, combined with the easy setup and helpful support, convinced the team that CircleCI was the perfect solution."]
            [:blockquote
             {:cite "http://www.shopify.com"}
             [:p
              "One of my favorite things about CircleCI is that their team really cares about making sure their customers get maximum value out of their product."]
             [:cite
              [:img {:src (stefon/asset-path "/img/outer/stories/arthur.jpg")}]
              [:ul
               [:li "Arthur Neves"]
               [:li "Developer, Shopify"]]]]]
           [:section
            [:h2 "Implementation"]
            [:p
             "CircleCI integrates natively with GitHub, which Shopify was already using, so set up time was minimal; it only took a few minutes to follow the rest of their repos on CircleCI and to invite the rest of their team members. Once their tests were running, they started optimizing their containers and parallelization from within the CircleCI app so that their test suite would run as quickly as possible. Once they had the tests running for all their projects, the next step was setting up Continuous Deployment."]
            [:p
             "CD has always been a core part of the engineering culture at Shopify, so getting deployment set up with CircleCI was essential. To streamline their CD process, Shopify used the CircleCI API to build a custom 'Ship It' tool that allows any developer to deploy with the press of a button, as long as they have a green build on CircleCI. All they had to do to build this was verify that the pull request in question returned \"outcome\" : \"success\" from the CircleCI API after merging with master, and then allow the developer to deploy."]
            [:p
             "This same functionality can also be accomplished without using the API by putting the deployment script directly into the circle.yml file."]
            shared/stories-procedure]
           [:section
            [:h2 "Results"]
            [:p
             "Today, 1 year after initially switching to CircleCI, Shopify has scaled their engineering team to 130 team members who on average merge 300 pull requests and deploy 100 times per week. Thanks to CircleCI, they've managed to maintain their agile and efficient development process, with new projects being added effortlessly and everyone working off of a master branch (rather than having to maintain production and development branches). Their test suite runs faster than it ever did with their previous solution, and now that developers don't have to run tests on their local machine they can work on other projects while CircleCI runs their tests in the background. Shopify also uses CircleCI along with Capistrano to continuously deploy their application for anything from a small bug fix, to a package upgrade, to a new feature."]
            [:blockquote
             {:cite "http://www.shopify.com"}
             [:p
              "CircleCI lets us be more agile and ship product faster. We can focus on delivering value to our customers, not maintaining Continuous Integration and Delivery infrastructure."]
             [:cite
              [:img {:src (stefon/asset-path "/img/outer/stories/john.jpg")}]
              [:ul
               [:li "John Duff"]
               [:li "Director of Engineering, Shopify"]]]]
            [:p
             "The Shopify team no longer has to worry about scaling their testing infrastructure, maintaining their test stack, or monitoring their deployments. They focus on building products that bring value to their customers while relying on CircleCI to ensure that they are able to get those products to market quickly and reliably."]]
           (if (:split_form ab-tests)
             (om/build cta-split app)
             (om/build cta-simple app))]])))))
