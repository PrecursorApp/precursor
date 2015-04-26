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

(def artwork-voice
  (html
   [:div.art-frame
    [:div.art-team.artwork
     [:div.art-voice-list
      [:div.art-voice-items
       [:div.art-voice-item
        [:div.art-voice-user.a (common/icon :user)]
        [:div.art-voice-name.a "Raph"]
        [:div.art-voice-talk.a (common/icon :sound-off)]]
       [:div.art-voice-item.selected
        [:div.art-voice-user.b (common/icon :user)]
        [:div.art-voice-name.b "Leo"]
        [:div.art-voice-talk.b (common/icon :sound-off)]
        [:div.art-voice-make.b.focus (common/icon :sound-max)]]
       [:div.art-voice-item
        [:div.art-voice-user.c (common/icon :user)]
        [:div.art-voice-name.c "Donnie"]
        [:div.art-voice-talk.c (common/icon :sound-off)]]
       [:div.art-voice-item
        [:div.art-voice-user.d (common/icon :user)]
        [:div.art-voice-name.d "Mikey"]
        [:div.art-voice-talk.d (common/icon :sound-mute)]]]]]]))

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
           {:data-before "Or "}
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
             [:p "That's why we made Precursor."]
             [:div.calls-to-action
              (om/build make-button (select-keys app [:document/id]))]]]]
          [:div.our-proof
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
          [:div.landing-learn-back {:class (when (and (:show-scroll-to-arrow app) (not (contains? active-features "1"))) " show ")}]
          [:div.landing-learn-front {:class (when-not (contains? active-features "1") " show ")}
           [:a {:role "button" :on-click #(cast! :scroll-to-arrow-clicked)}
            "Learn more."]]
          [:div.feature.content {:class (when (contains? active-features "1") "art-visible") :ref "1"}
           [:div.feature-story
            [:h2.content-copy
             "Collaborate with ease."]
            [:p.content-copy
             "Our team features are optimized to make collaborating in real-time effortless. "
             "Communicate and create new ideas with your teammates in one secure place. "]
            [:a.feature-link {:href "/features/team"}
             "See team features."]]
           [:div.feature-media artwork-voice]]
          [:div.feature-divider]
          [:div.feature.content {:class (when (contains? active-features "2") "art-visible") :ref "2"}
           [:div.feature-story
            [:h2.content-copy
             "Make real interactions."]
            [:p.content-copy
             "Easily link your wireframes together in minutes to create working demos of your idea. "
             "You'll save time by pinpointing areas for improvement before you go into development. "]
            [:a.feature-link {:href "/blog/interactive-layers"}
             "Read our tutorial."]]
           [:div.feature-media.reverse artwork-interact]]
          [:div.feature-divider]
          [:div.feature.content {:class (when (contains? active-features "3") "art-visible") :ref "3"}
           [:div.feature-story
            [:h2.content-copy
             "Prototype ideas anywhere."]
            [:p.content-copy
             "We designed our interface to get out of the way and let you concentrate on your ideas. "
             "It's simple enough to show on your phone and perfect for collaborating on your tablet. "]
            [:a.feature-link {:href "https://precursorapp.com/document/17592197661694"}
             "Try on iPhone."]]
           [:div.feature-media artwork-mobile]]])))))

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
             [:h1 "It's a blackboard designed to help teams brainstorm."]
             [:p "Precursor is no-nonsense prototyping."]
             (if (utils/logged-in? owner)
               [:div.calls-to-action
                (om/build make-button (select-keys app [:document/id])
                          {:opts {:alt "alt"}})
                [:a.pancake-button {:href "/trial/team"} "Start a free trial."]])]]]])))))

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
