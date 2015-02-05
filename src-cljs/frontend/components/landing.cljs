(ns frontend.components.landing
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
            [frontend.datascript :as ds]
            [frontend.models.doc :as doc-model]
            [frontend.overlay :refer [current-overlay overlay-visible? overlay-count]]
            [frontend.scroll :as scroll]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.date :refer (date->bucket)]
            [goog.dom]
            [goog.labs.userAgent.browser :as ua]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]])
  (:import [goog.ui IdGenerator]))


(def screen
  [:svg {:view-box "0 0 1024 640"}
   [:path.shape-layers {:d "M704,576H64V88h640V576z M248,152h352 M256,184h400 M664,216H248 M240,248h408 M96,280h544 M96,312h528 M96,344h560 M96,376h512 M672,408H96v168h576V408z M96,408 l336,168 M336,576l336-168"}]
   [:g.in-progress [:circle {:stroke-dasharray "8.0417,8.0417", :cx "160", :cy "184", :r "64"}]]
   [:g.in-progress
    [:line {:x1 "205.3", :y1 "138.7", :x2 "202.4", :y2 "141.6"}]
    [:line {:stroke-dasharray "8,8", :x1 "196.8", :y1 "147.2", :x2 "120.4", :y2 "223.6"}]
    [:line {:x1 "117.6", :y1 "226.4", :x2 "114.7", :y2 "229.3"}]]
   [:g.in-progress
    [:line {:x1 "205.3", :y1 "229.3", :x2 "202.4", :y2 "226.4"}]
    [:line {:stroke-dasharray "8,8", :x1 "196.8", :y1 "220.8", :x2 "120.4", :y2 "144.4"}]
    [:line {:x1 "117.6", :y1 "141.6", :x2 "114.7", :y2 "138.7"}]]
   [:path.chat {:d "M768,24v616 M768,576h256 M784,592v32"}]
   [:path.cursor {:d "M183.3,256.1v-13.4l9.5,9.5h-3.8 l2.2,5.3l-2.9,1.2l-2.2-5.3L183.3,256.1z"}]
   [:path.menu {:d "M1016,0H8C3.6,0,0,3.6,0,8v16h1024V8 C1024,3.6,1020.4,0,1016,0z"}]
   [:path.border {:d "M0,24v608c0,4.4,3.6,8,8,8h1008 c4.4,0,8-3.6,8-8V24"}]
   [:path.actions {:d "M16,12c0,2.2-1.8,4-4,4s-4-1.8-4-4s1.8-4,4-4S16,9.8,16,12z M44,8c-2.2,0-4,1.8-4,4s1.8,4,4,4 c2.2,0,4-1.8,4-4S46.2,8,44,8z M28,8c-2.2,0-4,1.8-4,4s1.8,4,4,4s4-1.8,4-4S30.2,8,28,8z"}]])

(defn past-center? [owner ref]
  (let [node (om/get-node owner ref)
        vh (.-height (goog.dom/getViewportSize))]
    (< (.-top (.getBoundingClientRect node)) (/ vh 2))))

;; TODO: update to new om so that we don't need this
(defn maybe-set-state! [owner korks value]
  (when (not= (om/get-state owner korks) value)
    (om/set-state! owner korks value)))

(defn landing [app owner]
  (reify
    om/IInitState (init-state [_] {:past-center-featurettes #{}})
    om/IRenderState
    (render-state [_ {:keys [past-center-featurettes]}]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:div.app-landing {:on-scroll #(maybe-set-state! owner [:past-center-featurettes]
                                                          (set (filter (partial past-center? owner) ["1" "2" "3" "4" "5"])))}
           [:nav.home-nav
            [:div.content
             [:a.nav-item {:role "button"} "Precursor"]
             [:a.nav-item {:role "button"} "Pricing"]
             [:a.nav-item {:role "button"} "Blog"]
             [:a.nav-item.google-login.mobile-hidden {:role "button"}
              [:span.google-login-icon
               (common/icon :google)]
              [:span.google-login-body "Sign in"]]]]
           [:div.prolog.jumbotron
            [:div.content
             [:h1 "Collaborate on every idea with your entire team."]
             [:h4 "Productive prototyping without all the nonsense."]
             [:button.prolog-cta {:on-click #(cast! :landing-closed)}
              "Launch Precursor"]]
            [:div.trusted-by]]
           [:div.home-body
            [:article.featurette.featurette-why {:ref "1"
                                                 :class (when (contains? past-center-featurettes "1") "active")}
             [:div.featurette-story
              [:h2 "Sharing prototypes should be simple."]
              [:p "Lorem ipsum dolor sit amet, consectetur adipiscing elit.
                  Sed felis enim, rhoncus a lobortis at, porttitor nec tellus.
                  Aliquam gravida consequat velit, ultrices porttitor turpis sagittis et."]]
             [:div.featurette-media screen]]
            [:article.featurette.featurette-how  {:ref "2"
                                                  :class (when (contains? past-center-featurettes "2") "active")}
             [:div.featurette-story
              [:h2 "Express your ideas efficiently with simple tools."]
              [:p "Lorem ipsum dolor sit amet, consectetur adipiscing elit.
                  Sed felis enim, rhoncus a lobortis at, porttitor nec tellus.
                  Aliquam gravida consequat velit, ultrices porttitor turpis sagittis et."]]
             [:div.featurette-media screen]]
            [:article.featurette.featurette-how {:ref "3"
                                                 :class (when (contains? past-center-featurettes "3") "active")}
             [:div.featurette-story
              [:h2 "Interact with your idea before developing it."]
              [:p "Lorem ipsum dolor sit amet, consectetur adipiscing elit.
                  Sed felis enim, rhoncus a lobortis at, porttitor nec tellus.
                  Aliquam gravida consequat velit, ultrices porttitor turpis sagittis et."]]
             [:div.featurette-media screen]]
            [:article.featurette.featurette-how {:ref "4"
                                                 :class (when (contains? past-center-featurettes "4") "active")}
             [:div.featurette-story
              [:h2 "Share your ideas faster without forgetting them."]
              [:p "Lorem ipsum dolor sit amet, consectetur adipiscing elit.
                  Sed felis enim, rhoncus a lobortis at, porttitor nec tellus.
                  Aliquam gravida consequat velit, ultrices porttitor turpis sagittis et."]]
             [:div.featurette-media screen]]
            [:article.featurette.featurette-what {:ref "5"
                                                  :class (when (contains? past-center-featurettes "5") "active")}
             [:div.featurette-story
              [:h2 "Pure prototyping, just focus on the idea."]
              [:p "Lorem ipsum dolor sit amet, consectetur adipiscing elit.
                  Sed felis enim, rhoncus a lobortis at, porttitor nec tellus.
                  Aliquam gravida consequat velit, ultrices porttitor turpis sagittis et."]]
             [:div.featurette-media screen]]]
           [:div.epilog.jumbotron
            [:div.content
             [:h1 "Collaborate on every idea with your entire team."]
             [:h4 "Productive prototyping without all the nonsense."]
             ; [:div.jumbotron-buttons
             ;  [:a.google-login {:role "button"}
             ;   [:span.google-login-icon
             ;    (common/icon :google)]
             ;   [:span.google-login-body "Sign in with Google"]]
             ;  [:button "Try it first"]]

             [:div.jumbotron-buttons
              [:a.google-login.epilog-cta {:role "button"}
               [:span.google-login-icon
                (common/icon :google)]
               [:span.google-login-body "Sign in with Google"]]
              [:a.epilog-cta-2nd {:on-click #(cast! :landing-closed)
                                  :role "button"}
               "Or Try It First"]]

             ; [:a.google-login {:role "button"}
             ;  [:span.google-login-icon
             ;   (common/icon :google)]
             ;  [:span.google-login-body "Sign in with Google"]]
             ; [:a {:role "button"} "Or try it first"]

             ]]
           [:nav.home-foot
            [:div.content
             [:a.nav-item {:role "button"} "Precursor"]
             [:a.nav-item {:role "button"} "Pricing"]
             [:a.nav-item {:role "button"} "Blog"]]]])))))
