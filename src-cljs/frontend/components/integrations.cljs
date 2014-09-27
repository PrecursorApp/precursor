(ns frontend.components.integrations
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

(def circle-logo
  [:svg#Layer_1 {:y "0px", :xml:space "preserve", :x "0px", :viewBox "0 0 100 100", :version "1.1", :enable-background "new 0 0 100 100"}
   [:path#mark_21_ {:fill "#054C64", :d "M49.5,39.2c5.8,0,10.5,4.7,10.5,10.5s-4.7,10.5-10.5,10.5c-5.8,0-10.5-4.7-10.5-10.5\n\tS43.7,39.2,49.5,39.2z M49.5,5.5c-20.6,0-38,14.1-42.9,33.2c0,0.2-0.1,0.3-0.1,0.5c0,1.2,0.9,2.1,2.1,2.1h17.8\n\tc0.9,0,1.6-0.5,1.9-1.2c0,0,0-0.1,0-0.1c3.7-7.9,11.7-13.4,21-13.4c12.8,0,23.2,10.4,23.2,23.2C72.7,62.6,62.3,73,49.5,73\n\tc-9.3,0-17.4-5.5-21-13.4c0,0,0-0.1,0-0.1c-0.3-0.7-1.1-1.2-1.9-1.2H8.7c-1.2,0-2.1,0.9-2.1,2.1c0,0.2,0,0.4,0.1,0.5\n\tC11.6,79.9,28.9,94,49.5,94C74,94,93.8,74.2,93.8,49.8S74,5.5,49.5,5.5z"}"/"]])

(def docker-logo
  [:svg#Layer_1 {:y "0px", :x "0px", :viewBox "0 0 100 100", :version "1.1", :space "preserve", :enable-background "new 0 0 100 100"}
   [:g
    [:rect {:y "39.6", :x "41.4", :width "9", :height "9", :fill "#054C64"}]
    [:rect {:y "39.6", :x "51.7", :width "9", :height "9", :fill "#054C64"}]
    [:rect {:y "39.6", :x "31", :width "9", :height "9", :fill "#054C64"}]
    [:rect {:y "39.6", :x "20.7", :width "9", :height "9", :fill "#054C64"}]
    [:rect {:y "39.6", :x "10.4", :width "9", :height "9", :fill "#054C64"}]
    [:rect {:y "29.2", :x "41.4", :width "9", :height "9", :fill "#054C64"}]
    [:rect {:y "18.8", :x "41.4", :width "9", :height "9", :fill "#054C64"}]
    [:rect {:y "29.2", :x "31", :width "9", :height "9", :fill "#054C64"}]
    [:rect {:y "29.2", :x "20.7", :width "9", :height "9", :fill "#054C64"}]
    [:path {:fill "#054C64",
            :d "M6,70.5l-0.2-0.3c-0.6-0.8-1.1-1.6-1.6-2.4l-0.8-1.4c-0.4-0.8-0.8-1.7-1.2-2.7l-0.1-0.2\r\n\t\tc0-0.1-0.1-0.3-0.1-0.4c-0.1-0.2-0.2-0.5-0.2-0.7c-1-2.9-1.4-6.1-1.4-9.4c0-1,0.1-1.8,0.1-2.6l0.1-0.7h67.1c4.9,0,9.6-1.7,12.1-3.6\r\n\t\tc-1.6-1.7-2.6-4.1-2.9-7c-0.3-3.6,0.6-7.2,2.3-9.2l0.5-0.6l0.6,0.5c2,1.6,6.2,5.6,6.2,11c1.8-0.7,3.8-1,5.8-1\r\n\t\tc2.5,0,4.8,0.5,6.7,1.7l0.6,0.4l-0.3,0.7c-2.2,4.3-6.8,6.7-12.9,6.7l0,0c-0.6,0-1.3,0-1.9-0.1c-8.2,21.2-26.5,32.9-51.5,32.9\r\n\t\tc-4.4,0-8.6-0.6-12.4-1.8c-5.4-1.7-9.8-4.5-13.2-8.3l-0.7-0.8 M37.1,80.4c-6.1-2.7-9.5-6.5-11.5-10.8c-2.3,0.7-4.9,1.2-8.1,1.4\r\n\t\tc-1.2,0.1-2.4,0.1-3.7,0.2c-1.5,0-3.1,0-4.8,0c5.7,5.4,12.6,9.5,25.3,9.3C35.3,80.5,36.2,80.5,37.1,80.4z M30.3,63.5\r\n\t\tc-1.4,0-2.4,1.1-2.4,2.4c0,1.4,1.1,2.4,2.4,2.4c1.4,0,2.4-1.1,2.4-2.4C32.7,64.6,31.7,63.5,30.3,63.5"}]
    [:path {:fill-rule "evenodd",
            :fill "#054C64",
            :d "M30.3,64.2c0.2,0,0.4,0,0.6,0.1c-0.2,0.1-0.3,0.3-0.3,0.6\r\n\t\tc0,0.4,0.3,0.7,0.7,0.7c0.3,0,0.5-0.1,0.6-0.4c0.1,0.2,0.2,0.4,0.2,0.7c0,1-0.8,1.8-1.8,1.8c-1,0-1.7-0.8-1.7-1.8\r\n\t\tC28.6,64.9,29.4,64.2,30.3,64.2",
            :clip-rule "evenodd"}]]])

(defn cta-form [app owner]
  (reify
    om/IInitState
    (init-state [_]
                {:email ""
                 :use-case ""
                 :notice nil
                 :loading? false})
    om/IRenderState
    (render-state [_ {:keys [email use-case notice loading?]}]
                  (let [controls-ch (om/get-shared owner [:comms :controls])
                        clear-notice! #(om/set-state! owner [:notice] nil)
                        clear-form! (fn [& [notice]]
                                      (om/update-state! owner (fn [s]
                                                                (merge s
                                                                       (when notice {:notice notice})
                                                                       {:email ""
                                                                        :use-case ""
                                                                        :loading? false}))))]
                    (html
                      [:form
                       [:input {:name "Email",
                                :required true
                                :value email
                                :on-change #(do (clear-notice!) (om/set-state! owner [:email] (.. % -target -value)))
                                :type "text"}]
                       [:label {:alt "Email (required)", :placeholder "Email"}]
                       [:textarea
                        {:name "docker_use__c",
                         :required true
                         :value use-case
                         :on-change #(do (clear-notice!) (om/set-state! owner [:use-case] (.. % -target -value)))
                         :type "text"}]
                       [:label {:placeholder "How do you want to use it?"}]
                       [:div.notice
                        (when notice
                          [:span {:class (:type notice)} (:message notice)])]
                       [:div.submit
                        [:input
                         {:value (if loading? "Submitting.." "Submit"),
                          :class (when loading? "disabled")
                          :on-click #(do (if (or (empty? email) (not (utils/valid-email? email)))
                                           (om/set-state! owner [:notice] {:type "error"
                                                                           :message "Please enter a valid email address."})
                                           (do
                                             (om/set-state! owner [:loading?] true)
                                             (go (let [resp (<! (marketo/submit-munchkin-form 1036 {:Email email
                                                                                                    :docker_use__c use-case}))]
                                                   (if-not (= :success (:status resp))
                                                     (om/update-state! owner (fn [s]
                                                                               (merge s {:loading? false
                                                                                         :notice {:type "error"
                                                                                                  :message "Sorry! There was an error submitting the form. Please try again or email sayhi@circleci.com."}})))
                                                     (clear-form! {:type "success"
                                                                   :message "Thanks! We will be in touch soon."}))))))
                                       false)
                          :type "submit"}]]])))))

(defn docker [app owner]
  (reify
    om/IRender
    (render [_]
            (let [controls-ch (om/get-shared owner [:comms :controls])
                  ab-tests (:ab-tests app)]
              (html
                [:div#integrations.docker
                 [:div.section-container
                  [:section.integrations-hero-wrapper
                   [:article.integrations-hero-title
                    [:h1 "Build and deploy Docker containers on CircleCI."]]
                   [:article.integrations-hero-units
                    [:div.integrations-hero-unit
                     circle-logo
                     [:p "CircleCI makes modern Continuous Integration and Deployment easy."]]
                    [:div.integrations-hero-unit
                     docker-logo
                     [:p "Docker makes it easy to build, run, and ship applications anywhere.  "]]]
                   [:h3.message
                    "Together they let you achieve development-production parity. At last.
                    "]]]
                 [:div.section-container
                  [:section.integrations-hero-wrapper
                   [:div.docker-features
                    [:div.feature-container
                     [:div.feature
                      [:img {:src (utils/cdn-path "/icons/cloud-circle.png")}]
                      [:h3 "Continous Deployment of your Docker images"]
                      [:p
                       "CircleCI makes it easy to deploy images to Docker Hub as well as to continuously deploy applications to AWS Elastic Beanstalk, Google Compute Engine, and others."]
                      [:a {:href "/docs/docker"} "docs"]]
                     [:div.feature
                      [:img {:src (utils/cdn-path "/icons/scale-circle.png")}]
                      
                      [:h3 "Dev-production parity"]
                      [:p
                       "Docker containers let you remove almost all of the variables that differ between your test and production environments. You can specify everything from your Linux distro to what executables run at startup in a Dockerfile, build all of that information into a Docker image, test it, and deploy the exact same image byte-for-byte to production. You can now run this entire process on CircleCI."]
                      [:a {:href "/docs/docker"} "docs"]]
                     [:div.feature
                      [:img {:src (utils/cdn-path "/icons/wrench-circle.png")}]
                      [:h3 "Full Docker functionality"]
                      [:p
                       "You can now use all Docker functionality within the CircleCI build environment. All of the usual Docker command-line commands work as expected, so you can build and run Docker containers to your heart's content."]
                      [:a {:href "/docs/docker"} "docs"]]]]]]
                 [:div.section-container
                  [:section.integrations-hero-wrapper
                   [:p.center-text
                    "Docker is enabled for all current CircleCI users, everyone else can sign up below!"]
                   [:div.docker-cta
                    [:div.ctabox {:class "line"}
                     [:div
                      [:p "Plans start at $19 per month. All plans include a free 14 day trial."]]
                     (shared/home-button {:source "integrations/docker"} controls-ch)
                     [:div
                      [:p
                       [:i "CircleCI keeps your code safe. "
                        [:a {:href "/security" :title "Security"} "Learn how."]]]]]
                    ]]]])))))
