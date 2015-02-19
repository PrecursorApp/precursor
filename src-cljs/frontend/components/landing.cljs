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
  (:require-macros [sablono.core :refer (html)])
  (:import [goog.ui IdGenerator]))

(def team-sharing
  [:svg.art {:view-box "0 0 1024 640"}
   [:g
    [:path.art-both-color {:d "M1008,8H16c-4.4,0-8,3.6-8,8v16h1008V16 C1016,11.6,1012.4,8,1008,8z"}]
    [:path.art-fill-white {:d "M24,20c0,2.2-1.8,4-4,4s-4-1.8-4-4s1.8-4,4-4S24,17.8,24,20z M52,16c-2.2,0-4,1.8-4,4s1.8,4,4,4s4-1.8,4-4 S54.2,16,52,16z M36,16c-2.2,0-4,1.8-4,4s1.8,4,4,4s4-1.8,4-4S38.2,16,36,16z"}]
    [:path.art-line-color {:d "M8,32v592c0,4.4,3.6,8,8,8h992c4.4,0,8-3.6,8-8V32"}]]
   [:g
    [:path.art-line-color {:d "M80,80c0,13.3-10.7,24-24,24S32,93.3,32,80 s10.7-24,24-24S80,66.7,80,80z M50.3,73.7c0,3.1,2.5,5.7,5.7,5.7c3.1,0,5.7-2.5,5.7-5.7c0-3.1-2.5-5.7-5.7-5.7 C52.9,68,50.3,70.5,50.3,73.7z M62.7,78.9c2.1,1.7,3.4,4.9,3.4,6.9c0,3.9-4.5,6.2-10.1,6.2c-5.6,0-10.1-2.3-10.1-6.2 c0-2.1,1.3-5.2,3.4-6.9"}]
    [:text.art-fill-black {:transform "matrix(1 0 0 1 104 72)"} "email@email.com"]
    [:text.art-fill-color {:transform "matrix(1 0 0 1 104 96)"} "Was granted access yesterday."]]
   [:g
    [:path.art-line-color {:d "M80,152c0,13.3-10.7,24-24,24s-24-10.7-24-24 s10.7-24,24-24S80,138.7,80,152z M50.3,145.7c0,3.1,2.5,5.7,5.7,5.7c3.1,0,5.7-2.5,5.7-5.7S59.1,140,56,140 C52.9,140,50.3,142.5,50.3,145.7z M62.7,150.9c2.1,1.7,3.4,4.9,3.4,6.9c0,3.9-4.5,6.2-10.1,6.2c-5.6,0-10.1-2.3-10.1-6.2 c0-2.1,1.3-5.2,3.4-6.9"}]
    [:text.art-fill-black {:transform "matrix(1 0 0 1 104 144)"} "email@email.com"]
    [:text.art-fill-color {:transform "matrix(1 0 0 1 104 168)"} "Was granted access yesterday."]]
   [:g
    [:path.art-line-color {:d "M80,224c0,13.3-10.7,24-24,24s-24-10.7-24-24 s10.7-24,24-24S80,210.7,80,224z M50.3,217.7c0,3.1,2.5,5.7,5.7,5.7c3.1,0,5.7-2.5,5.7-5.7S59.1,212,56,212 C52.9,212,50.3,214.5,50.3,217.7z M62.7,222.9c2.1,1.7,3.4,4.9,3.4,6.9c0,3.9-4.5,6.2-10.1,6.2c-5.6,0-10.1-2.3-10.1-6.2 c0-2.1,1.3-5.2,3.4-6.9"}]
    [:text.art-fill-black {:transform "matrix(1 0 0 1 104 216)"} "email@email.com"]
    [:text.art-fill-color {:transform "matrix(1 0 0 1 104 240)"} "Was granted access yesterday."]]
   [:g
    [:path.art-line-color {:d "M80,296c0,13.3-10.7,24-24,24s-24-10.7-24-24 s10.7-24,24-24S80,282.7,80,296z M50.3,289.7c0,3.1,2.5,5.7,5.7,5.7c3.1,0,5.7-2.5,5.7-5.7S59.1,284,56,284 C52.9,284,50.3,286.5,50.3,289.7z M62.7,294.9c2.1,1.7,3.4,4.9,3.4,6.9c0,3.9-4.5,6.2-10.1,6.2c-5.6,0-10.1-2.3-10.1-6.2 c0-2.1,1.3-5.2,3.4-6.9"}]
    [:text.art-fill-black {:transform "matrix(1 0 0 1 104 288)"} "email@email.com"]
    [:text.art-fill-color {:transform "matrix(1 0 0 1 104 312)"} "Was granted access yesterday."]]
   [:g
    [:path.art-line-color {:d "M80,368c0,13.3-10.7,24-24,24s-24-10.7-24-24 s10.7-24,24-24S80,354.7,80,368z M50.3,361.7c0,3.1,2.5,5.7,5.7,5.7c3.1,0,5.7-2.5,5.7-5.7S59.1,356,56,356 C52.9,356,50.3,358.5,50.3,361.7z M62.7,366.9c2.1,1.7,3.4,4.9,3.4,6.9c0,3.9-4.5,6.2-10.1,6.2c-5.6,0-10.1-2.3-10.1-6.2 c0-2.1,1.3-5.2,3.4-6.9"}]
    [:text.art-fill-black {:transform "matrix(1 0 0 1 104 360)"} "email@email.com"]
    [:text.art-fill-color {:transform "matrix(1 0 0 1 104 384)"} "Was granted access yesterday."]]
   [:g
    [:path.art-line-black {:d "M44.4,599.1v-2.7 c0-2.5-2-4.4-4.4-4.4s-4.4,2-4.4,4.4v2.7"}]
    [:path.art-fill-black {:d "M46.7,608H33.3v-8.9h13.3V608z"}]
    [:text.art-fill-black {:transform "matrix(1 0 0 1 72 608)"} "Private Document"]]
   [:g
    [:path.art-line-color {:d "M1016,96H392v472h624"}]
    [:path.art-line-color {:d "M822,568V256H586v312"}]
    [:path.art-line-color {:d "M586,471h156V353H586"}]]
   [:g
    [:path.art-line-color {:d "M822,504l-80-80 M586,268l85,85 M737,353l85-85 M586,504l33-33"}]
    [:path.art-line-color {:d "M586,504h236"}]
    [:path.art-line-color {:d "M822,268H586"}]]
   [:g
    [:path.art-line-color {:d "M600,425h112"}]
    [:path.art-line-color {:d "M600,441h64"}]
    [:path.art-line-color {:d "M600,457h96"}]]])

(def screen
  [:svg {:view-box "0 0 1024 640"}
   [:path.svg-shape-layers {:d "M704,576H64V88h640V576z M248,152h352 M256,184h400 M664,216H248 M240,248h408 M96,280h544 M96,312h528 M96,344h560 M96,376h512 M672,408H96v168h576V408z M96,408 l336,168 M336,576l336-168"}]
   [:g.svg-in-progress [:circle {:stroke-dasharray "8.0417,8.0417", :cx "160", :cy "184", :r "64"}]]
   [:g.svg-in-progress
    [:line {:x1 "205.3", :y1 "138.7", :x2 "202.4", :y2 "141.6"}]
    [:line {:stroke-dasharray "8,8", :x1 "196.8", :y1 "147.2", :x2 "120.4", :y2 "223.6"}]
    [:line {:x1 "117.6", :y1 "226.4", :x2 "114.7", :y2 "229.3"}]]
   [:g.svg-in-progress
    [:line {:x1 "205.3", :y1 "229.3", :x2 "202.4", :y2 "226.4"}]
    [:line {:stroke-dasharray "8,8", :x1 "196.8", :y1 "220.8", :x2 "120.4", :y2 "144.4"}]
    [:line {:x1 "117.6", :y1 "141.6", :x2 "114.7", :y2 "138.7"}]]
   [:path.svg-chat {:d "M768,24v616 M768,576h256 M784,592v32"}]
   [:path.svg-cursor {:d "M183.3,256.1v-13.4l9.5,9.5h-3.8 l2.2,5.3l-2.9,1.2l-2.2-5.3L183.3,256.1z"}]
   [:path.svg-menu {:d "M1016,0H8C3.6,0,0,3.6,0,8v16h1024V8 C1024,3.6,1020.4,0,1016,0z"}]
   [:path.svg-border {:d "M0,24v608c0,4.4,3.6,8,8,8h1008 c4.4,0,8-3.6,8-8V24"}]
   [:path.svg-actions {:d "M16,12c0,2.2-1.8,4-4,4s-4-1.8-4-4s1.8-4,4-4S16,9.8,16,12z M44,8c-2.2,0-4,1.8-4,4s1.8,4,4,4 c2.2,0,4-1.8,4-4S46.2,8,44,8z M28,8c-2.2,0-4,1.8-4,4s1.8,4,4,4s4-1.8,4-4S30.2,8,28,8z"}]])

(defn past-center? [owner ref]
  (let [node (om/get-node owner ref)
        vh (.-height (goog.dom/getViewportSize))]
    (< (.-top (.getBoundingClientRect node)) (/ vh 2))))

;; TODO: update to new om so that we don't need this
(defn maybe-set-state! [owner korks value]
  (when (not= (om/get-state owner korks) value)
    (om/set-state! owner korks value)))

(defn make-button [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            word-list (shuffle ["precursor"
                                "prototype"
                                "wireframe"
                                "user flow"
                                "diagram"
                                "ux flow"
                                "brainstorm"
                                "sketch"
                                "doodle"
                                "drawing"
                                "mockup"
                                "blueprint"
                                "masterpiece"
                                "document"
                                "flowchart"
                                "design"
                                "quick note"
                                "teammate happy"
                                "big square"
                                "presentation"
                                "giraffe"
                                "layout"
                                ])
            middle-index (int (/ (count word-list) 2))
            random-word (nth word-list (- (count word-list) 4))]
        (html
          [:div.make-button-wrap
           [:button.make-button
            {:on-click #(cast! :landing-closed)
             :on-mouse-enter #(om/refresh! owner)}
            [:div.make-prepend "Make a"]
            ;; [:div.make-prepend "Or make a"] ;; for bottom cta
            [:div.make-something
             [:div.something-default
              random-word]
             [:div.something-wheel
              {:data-before (str/join " " (drop-last 4 word-list))
               :data-after  (str/join " " (take-last 3 word-list))}
              random-word]]
            [:div.make-append "right now."]
            ;; [:div.make-append "first."] ;; for bottom cta
            ]])))))

(defn the-why [app owner]
  (reify
    om/IInitState (init-state [_] {:past-center-featurettes #{}})
    om/IRenderState
    (render-state [_ {:keys [past-center-featurettes]}]
      (let [cast! (om/get-shared owner :cast!)]
        (html
          [:div.the-why
           [:div.our-claim
            [:div.navigation
             [:div.content
              [:a {:role "button"} "Precursor"]
              [:a {:role "button"} "Pricing"]
              [:a {:role "button"} "Blog"]
              (common/google-login :small)]]
            [:div.our-philosphy-wrap
             [:div.our-philosphy.content
              [:h1 "Collaborating should be simple."]
              [:p "Prototype anywhere on any device. No nonsense, just what you need when you need it."]
              [:div.calls-to-action
               (om/build make-button app)]]]]
           [:div.our-proof
            ;; Hide this until we get testimonials/stats figured out
            ;; [:div.content "23,142 people have made 112,861 sketches in 27,100 documents."]
            ]])))))

(def artwork-ipad
  [:div.artwork
   [:div.a-ipad
    [:div.a-ipad-head
     [:div.a-ipad-camera]]
    [:div.a-ipad-body
     [:div.a-screen
      [:div.a-menu
       [:h4 "Today"]
       [:div.a-doc   [:img.a-doc-img {:src "https://prcrsr.com/document/17592196129062.svg"}]]
       [:div.a-doc   [:img.a-doc-img {:src "https://prcrsr.com/document/17592196129062.svg"}]]
       [:div.a-doc   [:img.a-doc-img {:src "https://prcrsr.com/document/17592196129062.svg"}]]
       [:div.a-doc   [:img.a-doc-img {:src "https://prcrsr.com/document/17592196129062.svg"}]]]
      [:div.a-canvas [:img.a-doc-img {:src "https://prcrsr.com/document/17592196129062.svg"}]]]]
    [:div.a-ipad-foot
     [:div.a-ipad-button]]]])

(def artwork-team
  [:div.artwork
   [:div.a-team
    [:div.a-team-list

     [:div.access-card
      [:div.access-avatar
       [:div.access-avatar-img]]
      [:div.access-details
       [:span "danny@precursorapp.com"]
       [:span.access-status "Was granted access yesterday."]]]

     [:div.access-card
      [:div.access-avatar
       [:div.access-avatar-img]]
      [:div.access-details
       [:span "danny@precursorapp.com"]
       [:span.access-status "Was granted access yesterday."]]]

     [:div.access-card
      [:div.access-avatar
       [:div.access-avatar-img]]
      [:div.access-details
       [:span "danny@precursorapp.com"]
       [:span.access-status "Was granted access yesterday."]]]

     [:div.access-card.requesting
      [:div.access-avatar
       [:div.access-avatar-img]]
      [:div.access-details
       [:span "danny@precursorapp.com"]
       [:span.access-status "Requested access on just now."]]]

     [:div.access-card
      [:div.access-avatar
       [:div.access-avatar-img]]
      [:div.access-details
       [:span "danny@precursorapp.com"]
       [:span.access-status "Was granted access yesterday."]]]

     [:div.access-card
      [:div.access-avatar
       [:div.access-avatar-img]]
      [:div.access-details
       [:span "danny@precursorapp.com"]
       [:span.access-status "Was granted access yesterday."]]]

     [:div.access-card
      [:div.access-avatar
       [:div.access-avatar-img]]
      [:div.access-details
       [:span "danny@precursorapp.com"]
       [:span.access-status "Was granted access yesterday."]]]]]])

(defn the-how [app owner]
  (reify
    om/IInitState (init-state [_] {:past-center-featurettes #{}})
    om/IRenderState
    (render-state [_ {:keys [past-center-featurettes]}]
      (let [cast! (om/get-shared owner :cast!)]
        (html
          [:div.the-how
            [:div.featurette.content
             {:class (when (contains? past-center-featurettes "1") "active") :ref "1"}
             [:div.featurette-story
              [:h2 "Access your ideas on any device right in the browser."]
              [:p "With Precursor all of your ideas are easily accessible right from the browser, whether you're on your desktop, tablet, or phone."]]
             [:div.featurette-media artwork-ipad]]
            [:div.featurette.content
             {:class (when (contains? past-center-featurettes "2") "active") :ref "2"}
             [:div.featurette-story
              [:h2 "Interact with your ideas way before development."]
              [:p "Make working demos in just minutes using our simple target linking."]
              [:p
               [:a
                {:role "button"}
                "Read the tutorial"]
               "."]]
             [:div.featurette-media.reverse artwork-ipad]]
            [:div.featurette.content
             {:class (when (contains? past-center-featurettes "3") "active") :ref "3"}
             [:div.featurette-story
              [:h2 "Collaborate with your whole team in real time."]
              [:p "Our new team features are optimized for efficient collaboration.
                  You'll have all of your team's best ideas store in one secure place."]
              [:p
               [:a
                {:role "button"}
                "Request free trial"]
               "."]]
             [:div.featurette-media artwork-team]]])))))

(defn the-what [app owner]
  (reify
    om/IInitState (init-state [_] {:past-center-featurettes #{}})
    om/IRenderState
    (render-state [_ {:keys [past-center-featurettes]}]
      (let [cast! (om/get-shared owner :cast!)]
        (html
          [:div.the-what
            [:div.our-proof]
            [:div.our-claim
             [:div.our-philosphy-wrap
              [:div.our-philosphy.content
               [:h1 "It's purely productive prototyping."]
               ; [:h1 "What you need when you need it."]
               [:p "Precursor is the easiest way to share ideas with your teammates, fast."]
               [:div.calls-to-action
                (common/google-login)
                (om/build make-button app)]]]
             [:div.navigation
              [:div.content
               [:a {:role "button"} "Precursor"]
               [:a {:role "button"} "Pricing"]
               [:a {:role "button"} "Blog"]]]]])))))

(defn landing [app owner]
  (reify
    om/IInitState (init-state [_] {:past-center-featurettes #{}})
    om/IRenderState
    (render-state [_ {:keys [past-center-featurettes]}]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:div.outer
          {:on-scroll #(maybe-set-state! owner [:past-center-featurettes] (set (filter (partial past-center? owner)["1" "2" "3"])))
           :class (when-not (get app :not-landing?) "landed")}
           (om/build the-why app)
           (om/build the-how app)
           (om/build the-what app)])))))
