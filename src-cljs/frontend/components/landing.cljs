(ns frontend.components.landing
  (:require [cemerick.url :as url]
            [clojure.set :as set]
            [clojure.string :as str]
            [cemerick.url :as url]
            [datascript :as d]
            [frontend.analytics :as analytics]
            [frontend.async :refer [put!]]
            [frontend.auth :as auth]
            [frontend.components.common :as common]
            [frontend.components.doc-viewer :as doc-viewer]
            [frontend.components.document-access :as document-access]
            [frontend.components.drawing :as drawing]
            [frontend.datascript :as ds]
            [frontend.models.doc :as doc-model]
            [frontend.overlay :refer [current-overlay overlay-visible? overlay-count]]
            [frontend.scroll :as scroll]
            [frontend.state :as state]
            [frontend.routes :as routes]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.ajax :as ajax]
            [frontend.utils.date :refer (date->bucket)]
            [goog.dom]
            [goog.labs.userAgent.browser :as ua]
            [goog.style]
            [goog.string :as gstring]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [sablono.core :refer (html)]
                   [cljs.core.async.macros :as am :refer [go go-loop alt!]])
  (:import [goog.ui IdGenerator]))

(def artwork-mobile
  (html
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
          [:img.art-doc-img {:src "https://precursorapp.com/document/17592196582600.svg"}]]]
        [:div.art-doc.selected
         [:div.art-doc-frame
          [:img.art-doc-img {:src "https://precursorapp.com/document/17592196582448.svg"}]]]
        [:div.art-doc
         [:div.art-doc-frame
          [:img.art-doc-img {:src "https://precursorapp.com/document/17592196581236.svg"}]]]
        [:div.art-doc
         [:div.art-doc-frame
          [:img.art-doc-img {:src "https://precursorapp.com/document/17592196129062.svg"}]]]]
       [:div.art-canvas
        [:div.art-doc-frame
         [:img.art-doc-img {:src "https://precursorapp.com/document/17592196582448.svg"}]]]
       [:div.art-screen-select]]]
     [:div.art-mobile-foot
      [:div.art-mobile-button]]]]))

(def artwork-interact
  (html
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
       [:div.property-dropdown-target "team page"]]]]]))

(def artwork-team
  (html
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
        [:span.access-status "Was granted access today."]]]]]]))

(defn make-button [{:keys [document/id]} owner {:keys [alt]}]
  (reify
    om/IDisplayName (display-name [_] "Landing Make Button")
    om/IInitState
    (init-state [_]
      {:word-list ["something"
                   "UI/UX"
                   "demos"
                   "stuff"
                   "lines"
                   "squares"
                   "circles"
                   "doodles"
                   "designs"
                   "layouts"
                   "diagrams"
                   "sketches"
                   "drawings"
                   "giraffes"
                   "projects"
                   "products"
                   "anything"
                   "something"
                   "new ideas"
                   "documents"
                   "prototypes"
                   "wireframes"
                   "user flows"
                   "flowcharts"
                   "interfaces"
                   "some stuff"
                   "life easier"
                   "experiences"
                   "brainstorms"
                   "masterpieces"
                   "collaboration"
                   "presentations"
                   "illustrations"
                   "walk-throughs"
                   "awesome ideas"
                   "with Precursor"
                   "teammates happy"
                   "your team happy"]})
    om/IDidMount
    (did-mount [_]
      (om/set-state! owner :widths (reduce (fn [acc word]
                                             (assoc acc word (.-width (goog.style/getSize (om/get-node owner word)))))
                                           {} (om/get-state owner :word-list))))
    om/IRenderState
    (render-state [_ {:keys [chosen-word-width word-list widths]}]
      (let [cast! (om/get-shared owner :cast!)
            nav-ch (om/get-shared owner [:comms :nav])
            chosen-word (first word-list)
            [before-words after-words] (partition-all (- (count word-list) 3) (rest word-list))]
        (html
         [:div.make-button
          {:class (when alt "alt")
           :role "button"
           :on-click #(do
                        (cast! :make-button-clicked))
           :on-touch-end #(do
                            (.preventDefault %)
                            (cast! :make-button-clicked))
           :on-mouse-enter #(om/set-state! owner :word-list (shuffle word-list))}
          [:div.make-prepend
           {:data-before "or "}
           "Make "]
          [:div.make-something
           [:div.something-default (when widths
                                     {:style {:width (get widths chosen-word)}})
            chosen-word]
           (when-not widths
             (for [word word-list]
               [:span {:style {:top "-1000px"
                               :left "-1000px"
                               :position "absolute"}
                       :ref word}
                word]))
           [:div.something-wheel
            (merge
             {:data-before (str/join " " before-words)
              :data-after  (str/join " " after-words)})
            chosen-word]]
          [:div.make-append
           {:data-before " first."}]])))))

(defn the-why [app owner]
  (reify
    om/IDisplayName (display-name [_] "Landing Why")
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:div.the-why
          [:div.our-claim
           [:div.our-philosophy-wrap
            [:div.our-philosophy.content
             [:h1 "Prototyping and team collaboration should be simple. "]
             [:p "And whiteboards weren't designed for teams. "]
             [:div.calls-to-action
              (om/build make-button (select-keys app [:document/id]))]]]]
          [:div.our-proof {:class (when (:show-scroll-to-arrow app) "extend")}
           ;; probably kill this when customers are ready
           (when (:show-scroll-to-arrow app)
             [:a {:role "button"
                  :on-click #(cast! :scroll-to-arrow-clicked)}
              (common/icon :arrow-down)])
           ;; Hide this until we get testimonials/stats figured out
           ;; [:div.content "23,142 people have made 112,861 sketches in 27,100 documents."]
           ]])))))

(defn past-center? [owner ref]
  (let [node (om/get-node owner ref)
        vh (.-height (goog.dom/getViewportSize))]
    (< (.-top (.getBoundingClientRect node)) (/ vh 2))))

(defn scrolled-back-out-of-view?
  "Checks if the user scrolled back up the page far enought that node is out of view"
  [owner ref]
  (let [node (om/get-node owner ref)
        vh (.-height (goog.dom/getViewportSize))
        rect (.getBoundingClientRect node)
        bottom (.-bottom rect)
        height (.-height rect)
        wiggle-room (* 0.1 vh)]
    (> bottom (- (+ vh height)
                 wiggle-room))))

(defn the-how [app owner]
  (reify
    om/IDisplayName (display-name [_] "Landing How")
    om/IInitState (init-state [_] {:active-features #{}})
    om/IDidMount (did-mount [_]
                   (scroll/register owner #(utils/maybe-set-state!
                                            owner
                                            [:active-features]
                                            (let [active-features (om/get-state owner [:active-features])
                                                  {active true inactive false} (group-by (partial contains? active-features)
                                                                                         ["1" "2" "3"])]
                                              (set (concat (filter (partial past-center? owner) inactive)
                                                           (remove (partial scrolled-back-out-of-view? owner) active)))))))
    om/IWillUnmount (will-unmount [_] (scroll/dispose owner))
    om/IRenderState
    (render-state [_ {:keys [active-features]}]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:div.the-how
          [:div.feature.content {:class (when (contains? active-features "1") "art-visible") :ref "1"}
           [:div.feature-story
            [:h2.content-copy
             "Make your ideas accessible anywhere, using any device. "]
            [:p.content-copy
             "Quickly find sketches on any tablet or phone and be ready when inspiration hits you. "
             "Don't bother wasting time with a big app download—just pull up your favorite browser. "]]
           [:div.feature-media artwork-mobile]]
          [:div.feature-divider]
          [:div.feature.content {:class (when (contains? active-features "2") "art-visible") :ref "2"}
           [:div.feature-story
            [:h2.content-copy
             "Make prototypes interactive & refine your user experience. "]
            [:p.content-copy
             "Easily link your wireframes together in minutes to create working demos of your idea. "
             "You'll save time by pinpointing areas for improvement before you go into development. "]
            [:a.feature-link.content-copy {:href "/blog/interactive-layers"}
             "Read the tutorial."]]
           [:div.feature-media.reverse artwork-interact]]
          [:div.feature-divider]
          [:div.feature.content {:class (when (contains? active-features "3") "art-visible") :ref "3"}
           [:div.feature-story
            [:h2.content-copy
             "Make team collaboration more productive & engaging. "]
            [:p.content-copy
             "Our team features are optimized to make collaborating in real-time effortless. "
             "Communicate and create new ideas with your teammates in one secure place. "]
            [:a.feature-link.content-copy {:href "/pricing"}
             "Try private docs."]]
           [:div.feature-media artwork-team]

           ]])))))

(defn the-what [app owner]
  (reify
    om/IDisplayName (display-name [_] "Landing What")
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:div.the-what
          [:div.our-proof]
          [:div.our-claim
           [:div.our-philosophy-wrap
            [:div.our-philosophy.content
             [:h1.philosophy-headline
              [:span.philosophy-needed "Precursor is pure prototyping."]]
             [:p.philosophy-subtext
              [:span.philosophy-needed "Real-time collaboration"]
              [:span.philosophy-excess " that makes it easy "]
              [:span.philosophy-excess " to focus on what's important"]
              [:span.philosophy-needed "."]]
             (if (utils/logged-in? owner)
               [:div.calls-to-action
                (om/build make-button (select-keys app [:document/id]))]

               [:div.calls-to-action
                (om/build common/google-login {:source "Landing What"})
                (om/build make-button (select-keys app [:document/id])
                          {:opts {:alt "alt"}})])]]]])))))

(defn landing [app owner]
  (reify
    om/IDisplayName (display-name [_] "Landing")
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:div.landing.page
          (om/build drawing/landing-background {:doc-id (:document/id app)
                                                :subscribers (get-in app [:subscribers :info])}
                    {:react-key "landing-background"})
          (om/build the-why app)
          (om/build the-how app)
          (om/build the-what app)])))))
