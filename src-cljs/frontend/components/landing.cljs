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
              [:p "Lorem ipsum dolor sit amet, consectetur adipiscing elit.
                  Sed felis enim, rhoncus a lobortis at, porttitor nec tellus.
                  Aliquam gravida consequat velit, ultrices porttitor turpis sagittis et."]]
             [:div.featurette-media screen]]
            [:div.featurette.content
             {:class (when (contains? past-center-featurettes "2") "active") :ref "2"}
             [:div.featurette-story
              [:h2 "Interact with your ideas way before development."]
              [:p "Lorem ipsum dolor sit amet, consectetur adipiscing elit.
                  Sed felis enim, rhoncus a lobortis at, porttitor nec tellus.
                  Aliquam gravida consequat velit, ultrices porttitor turpis sagittis et."]
              [:p
               [:a
                {:role "button"}
                "Read the tutorial."]]]
             [:div.featurette-media.reverse screen]]
            [:div.featurette.content
             {:class (when (contains? past-center-featurettes "3") "active") :ref "3"}
             [:div.featurette-story
              [:h2 "Collaborate with your whole team in real time."]
              [:p "Lorem ipsum dolor sit amet, consectetur adipiscing elit.
                  Sed felis enim, rhoncus a lobortis at, porttitor nec tellus.
                  Aliquam gravida consequat velit, ultrices porttitor turpis sagittis et."]
              [:p
               [:a
                {:role "button"}
                "See our pricing."]]]
             [:div.featurette-media screen]]])))))

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
               ; [:h1 "It's purely productive prototyping."]
               [:h1 "What you need when you need it."]
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
