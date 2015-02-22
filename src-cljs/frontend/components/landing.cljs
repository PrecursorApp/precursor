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
            [frontend.routes :as routes]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.date :refer (date->bucket)]
            [goog.dom]
            [goog.labs.userAgent.browser :as ua]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [sablono.core :refer (html)])
  (:import [goog.ui IdGenerator]))

(def artwork-mobile
  [:div.art-frame
   [:div.art-mobile.artwork
    [:div.art-mobile-head
     [:div.art-mobile-camera]]
    [:div.art-mobile-body
     [:div.art-screen
      [:div.art-menu
       [:div.art-heading "Today"]
       [:div.art-doc
        [:div.art-doc-frame
         [:img.art-doc-img {:src "https://prcrsr.com/document/17592196129062.svg"}]]]
       [:div.art-doc.selected
        [:div.art-doc-frame
         [:img.art-doc-img {:src "https://prcrsr.com/document/17592196129062.svg"}]]]
       [:div.art-doc
        [:div.art-doc-frame
         [:img.art-doc-img {:src "https://prcrsr.com/document/17592196129062.svg"}]]]
       [:div.art-doc
        [:div.art-doc-frame
         [:img.art-doc-img {:src "https://prcrsr.com/document/17592196129062.svg"}]]]]
      [:div.art-canvas
       [:div.art-doc-frame
        [:img.art-doc-img {:src "https://prcrsr.com/document/17592196129062.svg"}]]]
      [:div.art-screen-select]]]
    [:div.art-mobile-foot
     [:div.art-mobile-button]]]])

(def artwork-interact
  [:div.art-frame
   [:div.art-interact.artwork
    [:div.art-interact-button
     [:div.art-interact-text "HOME"]
     [:div.art-interact-cursor (common/icon :cursor)]]
    [:div.art-interact-head
     [:div.art-interact-name "home button"]
     [:div.art-interact-placeholder "link"]]
    [:div.art-interact-body
     [:div.property-dropdown-targets
      [:div.art-interact-target
       [:div.art-interact-placeholder "with"]
       [:div.art-interact-ibeam "|"]
       [:div.art-interact-more "..."]]
      [:div.property-dropdown-target.selected
       [:div.art-interact-item "home page"]]
      [:div.property-dropdown-target "blog page"]
      [:div.property-dropdown-target "about page"]
      [:div.property-dropdown-target "contact page"]
      [:div.property-dropdown-target "jobs page"]
      [:div.property-dropdown-target "team page"]]]]])

(def artwork-team
  [:div.art-frame
   [:div.art-team.artwork
    [:div.art-team-list
     [:div.access-card
      [:div.access-avatar
       [:div.access-avatar-img]]
      [:div.access-details
       [:span.access-name "niobe@prcr.sr"]
       [:span.access-status "Was granted access yesterday."]]]
     [:div.access-card
      [:div.access-avatar
       [:div.access-avatar-img]]
      [:div.access-details
       [:span.access-name "ballard@prcr.sr"]
       [:span.access-status "Was granted access yesterday."]]]
     [:div.access-card.selected
      [:div.access-avatar
       [:div.access-avatar-img]]
      [:div.access-details
       [:span.access-name "anderson@prcrsr.com"]
       [:span.access-status "Requested access yesterday."]]]
     [:div.access-card
      [:div.access-avatar
       [:div.access-avatar-img]]
      [:div.access-details
       [:span.access-name "smith@precursorapp.com"]
       [:span.access-status "Was denied access yesterday."]]]
     [:div.access-card
      [:div.access-avatar
       [:div.access-avatar-img]]
      [:div.access-details
       [:span.access-name "roland@prcr.sr"]
       [:span.access-status "Was granted access today."]]]]]])

(defn past-center? [owner ref]
  (let [node (om/get-node owner ref)
        vh (.-height (goog.dom/getViewportSize))]
    (< (.-top (.getBoundingClientRect node)) (/ vh 2))))

(defn maybe-set-state! [owner korks value]
  (when (not= (om/get-state owner korks) value)
    (om/set-state! owner korks value)))

(defn make-button [{:keys [document/id]} owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            nav-ch (om/get-shared owner [:comms :nav])
            word-list (shuffle ["demos"
                                "stuff"
                                "doodles"
                                "mockups"
                                "designs"
                                "layouts"
                                "details"
                                "diagrams"
                                "sketches"
                                "drawings"
                                "giraffes"
                                "projects"
                                "concepts"
                                "products"
                                "new ideas"
                                "documents"
                                "notebooks"
                                "precursors"
                                "prototypes"
                                "wireframes"
                                "user flows"
                                "flowcharts"
                                "interfaces"
                                "inventions"
                                "some stuff"
                                "experiences"
                                "brainstorms"
                                "big squares"
                                "masterpieces"
                                "presentations"
                                "illustrations"
                                "walk-throughs"
                                "awesome ideas"
                                "teammates happy"
                                "your team happy"])
            middle-index (int (/ (count word-list) 2))
            random-word (nth word-list (- (count word-list) 4))]
        (html
          [:div.make-button-wrap
           [:button.make-button
            {:role "button"
             :on-click #(do
                          (cast! :landing-closed)
                          (put! nav-ch [:navigate! {:path (str "/document/" id)}]))
             :on-touch-end #(do
                              (.preventDefault %)
                              (cast! :landing-closed)
                              (put! nav-ch [:navigate! {:path (str "/document/" id)}]))
             :on-mouse-enter #(om/refresh! owner)}
            [:div.make-prepend
             {:data-before "Or "}
             "Make "]
            [:div.make-something
             [:div.something-default
              random-word]
             [:div.something-wheel
              {:data-before (str/join " " (drop-last 4 word-list))
               :data-after  (str/join " " (take-last 3 word-list))}
              random-word]]
            [:div.make-append
             {:data-before " first"}
             "."]]])))))

(defn the-why [app owner]
  (reify
    om/IInitState (init-state [_] {:past-center-features #{}})
    om/IRenderState
    (render-state [_ {:keys [past-center-features]}]
      (let [cast! (om/get-shared owner :cast!)]
        (html
          [:div.the-why
           [:div.our-claim
            [:div.navigation
             [:div.content
              [:a.navigation-link {:href "/home" :target "_self" :role "button"} "Precursor"]
              [:a.navigation-link {:href ""      :target "_self" :role "button"} "Pricing"]
              [:a.navigation-link {:href "/blog" :target "_self" :role "button"} "Blog"]
              (common/google-login :small)]]
            [:div.our-philosphy-wrap
             [:div.our-philosphy.content
              [:h1.philosphy-headline
               [:span.philosphy-needed "Precursor wants to simplify"]
               [:span.philosphy-excess " your"]
               [:span.philosphy-needed " prototyping"]
               [:span.philosphy-excess " workflow"]
               [:span.philosphy-needed "."]]
              [:p.philosphy-subtext
               [:span.philosphy-needed "No nonsense—just what you need"]
               [:span.philosphy-excess ", when your team needs it"]
               [:span.philosphy-needed "."]]
              [:div.calls-to-action
               (om/build make-button (select-keys app [:document/id]))]]]]
           [:div.our-proof
            ;; Hide this until we get testimonials/stats figured out
            ;; [:div.content "23,142 people have made 112,861 sketches in 27,100 documents."]
            ]])))))

(defn the-how [app owner]
  (reify
    om/IInitState (init-state [_] {:past-center-features #{}})
    om/IDidMount (did-mount [_]
                   (scroll/register owner #(maybe-set-state! owner [:past-center-features]
                                                             (set (filter (partial past-center? owner)
                                                                          ["1" "2" "3"])))))
    om/IWillUnmount (will-unmount [_]
                      (scroll/dispose owner))
    om/IRenderState
    (render-state [_ {:keys [past-center-features]}]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:div.the-how
          [:div.feature.content
           {:class (when (contains? past-center-features "1") "art-visible") :ref "1"}
           [:div.feature-story
            [:h2.feature-headline
             [:span "Access your ideas on any device right in the browser."]]
            [:p.feature-copy
             [:span.content-copy
              "With Precursor all of your ideas are easily accessible right from the browser, whether you're on your desktop, tablet, or phone."]]]
           [:div.feature-media artwork-mobile]]
          [:div.feature-divider]
          [:div.feature.content
           {:class (when (contains? past-center-features "2") "art-visible") :ref "2"}
           [:div.feature-story
            [:h2.feature-headline
             [:span "Interact with your ideas way before development."]]
            [:p.feature-copy
             [:span.content-copy
              "Make working demos in just minutes using our simple target linking."]]
            [:a.feature-link {:href "/blog/interactive-layers" :role "button" :title "Read the tutorial."}
             [:span.content-copy
              "Read the tutorial."]]]
           [:div.feature-media.reverse artwork-interact]]
          [:div.feature-divider]
          [:div.feature.content
           {:class (when (contains? past-center-features "3") "art-visible") :ref "3"}
           [:div.feature-story
            [:h2.feature-headline
             [:span "Collaborate with your whole team in real time."]]
            [:p.feature-copy
             [:span.content-copy
              "Our new team features are optimized for efficient collaboration.
              You'll have all of your team's best ideas store in one secure place."]]
            [:a.feature-link {:role "button" :title "Request free trial."}
             [:span.content-copy
              "Request free trial."]]]
           [:div.feature-media artwork-team]]])))))

(defn the-what [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
          [:div.the-what
            [:div.our-proof]
            [:div.our-claim
             [:div.our-philosphy-wrap
              [:div.our-philosphy.content
               [:h1.philosphy-headline
                ; [:span "It's purely productive prototyping."]
                [:span "Precursor is pure prototyping."]
                ]
               ; [:h1 "What you need when you need it."]
               [:p.philosphy-subtext
                ; [:span.content-copy "Precursor is the easiest way to share ideas with your teammates, fast."]
                [:span.philosphy-subtext "Collaborating on an idea with your whole team is simple."]
                ; [:span.philosphy-subtext "Sharing ideas should be simple."]
                ; [:p "Prototype anywhere on any device. No nonsense, just what you need when you need it."]
                ]
               [:div.calls-to-action
                (common/google-login)
                (om/build make-button (select-keys app [:document/id]))]]]
             [:div.navigation
              [:div.content
               [:a.navigation-link {:href "/home" :target "_self" :role "button"} "Precursor"]
               [:a.navigation-link {:href ""      :target "_self" :role "button"} "Pricing"]
               [:a.navigation-link {:href "/blog" :target "_self" :role "button"} "Blog"]]]]])))))

(defn landing [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:div.outer.landing
          {:class (when-not (get app :not-landing?) "landed")}
          (om/build the-why app)
          (om/build the-how app)
          (om/build the-what app)])))))
