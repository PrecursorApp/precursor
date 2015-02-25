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

(defn early-access-component [app owner]
  (reify
    om/IInitState (init-state [_] {:company-name ""
                                   :employee-count ""
                                   :use-case ""
                                   :error nil})
    om/IDisplayName (display-name [_] "Early Access")
    om/IRenderState
    (render-state [_ {:keys [company-name employee-count use-case submitting? error submitted?]}]
      (let [{:keys [cast! handlers]} (om/get-shared owner)
            disabled? (or submitting? (empty? (:cust app)))
            submit-form (fn [args]
                          (go
                            (om/update-state! owner (fn [s]
                                                      (assoc s :submitting? true :error nil)))
                            ;; we wouldn't typically use ajax in a component--it's not advisable in
                            ;; this case, but we're short on time
                            (let [res (<! (ajax/managed-ajax :post "/api/v1/early-access"))]
                              (if (= :success (:status (utils/inspect res)))
                                (om/update-state! owner (fn [s]
                                                          (assoc s
                                                                 :submitting? false
                                                                 :submitted? true
                                                                 :error nil
                                                                 :company-name ""
                                                                 :employee-count ""
                                                                 :use-case "")))
                                (do
                                  (om/update-state! owner (fn [s]
                                                            (assoc s
                                                                   :submitting? false
                                                                   :error [:p "There was a problem submitting the form. Please try again or "
                                                                           [:a {:href (str "mailto:hi@precursorapp.com?"
                                                                                           (url/map->query {:subject "Early Access to Precursor"
                                                                                                            :body (str "Company name:\n"
                                                                                                                       company-name
                                                                                                                       "\n\nEmployee count:\n"
                                                                                                                       employee-count
                                                                                                                       "\n\nUse case:\n"
                                                                                                                       use-case)}))}
                                                                            "send us an email"]
                                                                           "."])))
                                  (put! (om/get-shared owner [:comms :errors]) [:api-error res]))))))]
        (html
         [:div.early-access
          [:div.early-access-content

           [:div.early-access-info
            [:h2.early-access-heading
             "We're excited to show you our team features."]
            [:p.early-access-copy
             "To activate your early access, please sign in and let us know about the following info.
              We'll send you an email confirmation once your account has been granted full access."]
            [:div.calls-to-action
             (om/build common/google-login {:source "Early Access Form"})]]

           ;; need to hook up disabled class
           [:div.early-access-form {:class (str (when disabled? "disabled ")
                                                (when submitting? "submitting ")
                                                (when submitted? "submitted "))}
            [:div.adaptive-placeholder {:tab-index "2"
                                        :ref "company-name"
                                        :data-before "What's your company's name?"
                                        :data-after "Company Name"
                                        :content-editable true
                                        :on-input #(let [value (goog.dom/getRawTextContent (.-target %))
                                                         stripped-value (gstring/stripNewlines value)]
                                                     (om/set-state! owner :company-name stripped-value)
                                                     ;; If they hit enter, send them to the next input.
                                                     (when (not= value stripped-value)
                                                       (.focus (om/get-node owner "employee-count"))))}

             company-name]
            [:div.adaptive-placeholder {:tab-index "3"
                                        :ref "employee-count"
                                        :data-before "How many employees are there?"
                                        :data-after "Employee Count"
                                        :content-editable true
                                        :on-input #(let [value (goog.dom/getRawTextContent (.-target %))
                                                         stripped-value (gstring/stripNewlines value)]
                                                     (om/set-state! owner :employee-count stripped-value)
                                                     ;; If they hit enter, send them to the next input.
                                                     (when (not= value stripped-value)
                                                       (.focus (om/get-node owner "use-case"))))}
             employee-count]
            [:div.adaptive-placeholder {:tab-index "4"
                                        :ref "use-case"
                                        :data-before "How will you use Precursor?"
                                        :data-after "Use Case"
                                        :content-editable true
                                        :on-input #(let [value (goog.dom/getRawTextContent (.-target %))
                                                         stripped-value (gstring/stripNewlines value)]
                                                     (om/set-state! owner :use-case stripped-value)
                                                     ;; If they hit enter, submit the form
                                                     (when (not= value stripped-value)
                                                       (.focus (om/get-node owner "submit-button"))
                                                       (.click (om/get-node owner "submit-button"))
                                                       #_(submit-form {:company-name company-name
                                                                     :employee-count employee-count
                                                                     ;; Have to make sure we get the latest value
                                                                     :use-case stripped-value})))}
             use-case]
            (when (seq error)
              [:div.error error])
            [:button.early-access-button {:tab-index "5"
                                          :ref "submit-button"
                                          :disabled disabled?
                                          :on-click #(submit-form {:company-name company-name
                                                                   :employee-count employee-count
                                                                   :use-case use-case})}
             "Request early access."]]]])))))



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

(defn navigation []
  (html
   [:div.navigation
    [:div.content
     [:a.navigation-link {:href "/home" :target "_self" :role "button" :title "Home"} "Precursor"]
     [:a.navigation-link {:href ""      :target "_self" :role "button" :title "Pricing"} "Pricing"]
     [:a.navigation-link {:href "/blog" :target "_self" :role "button" :title "Blog"} "Blog"]
     (om/build common/google-login {:source "Nav" :size :small})]]))

(defn make-button [{:keys [document/id]} owner]
  (reify
    om/IDisplayName (display-name [_] "Landing Make Button")
    om/IInitState
    (init-state [_]
      {:word-list ["demos"
                   "stuff"
                   "lines"
                   "squares"
                   "circles"
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
                   "precursors"
                   "prototypes"
                   "wireframes"
                   "user flows"
                   "flowcharts"
                   "interfaces"
                   "inventions"
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
                   "teammates happy"
                   "your team happy"]})
    om/IDidMount
    (did-mount [_]
      (om/set-state! owner :widths (time (reduce (fn [acc word]
                                                   (assoc acc word (.-width (goog.style/getSize (om/get-node owner word)))))
                                                 {} (om/get-state owner :word-list)))))
    om/IRenderState
    (render-state [_ {:keys [chosen-word-width word-list widths]}]
      (let [cast! (om/get-shared owner :cast!)
            nav-ch (om/get-shared owner [:comms :nav])
            chosen-word (first word-list)
            [before-words after-words] (partition-all (- (count word-list) 3) (rest word-list))]
        (html
         [:div.make-button
          {:role "button"
           :on-click #(do
                        (cast! :landing-closed)
                        (put! nav-ch [:navigate! {:path (str "/document/" id)}]))
           :on-touch-end #(do
                            (.preventDefault %)
                            (cast! :landing-closed)
                            (put! nav-ch [:navigate! {:path (str "/document/" id)}]))
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
           (navigation)
           [:div.our-philosphy-wrap
            [:div.our-philosphy.content
             [:h1.philosphy-headline
              [:span.philosphy-needed "Precursor wants to simplify"]
              [:span.philosphy-excess " your"]
              [:span.philosphy-needed " prototyping"]
              [:span.philosphy-excess " workflow"]
              [:span.philosphy-needed "."]]
             [:p.philosphy-subtext
              [:span.philosphy-needed "No nonsense—"]
              [:span.philosphy-needed "exactly what you need"]
              [:span.philosphy-excess " when you need it"]
              [:span.philosphy-needed "."]]
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
          [:div.feature.content
           {:class (when (contains? active-features "1") "art-visible") :ref "1"}
           [:div.feature-story
            [:h2.feature-headline
             [:span "Make your ideas accessible anywhere, using any device."]]
            [:p.feature-copy
             [:span.content-copy
              "Quickly find sketches on any tablet or phone and be ready when inspiration hits you.
              Don't bother wasting time with a big app download—just pull up your favorite browser."]]]
           [:div.feature-media artwork-mobile]]
          [:div.feature-divider]
          [:div.feature.content
           {:class (when (contains? active-features "2") "art-visible") :ref "2"}
           [:div.feature-story
            [:h2.feature-headline
             [:span "Make prototypes interactive & refine your user experience."]]
            [:p.feature-copy
             [:span.content-copy
              "Easily link your wireframes together in minutes to create working demos of your idea.
              You'll save time by pinpointing areas for improvement before you go into development."]]
            [:a.feature-link {:href "/blog/interactive-layers" :role "button" :title "Read the tutorial."}
             [:span.content-copy
              "Read the tutorial."]]]
           [:div.feature-media.reverse artwork-interact]]
          [:div.feature-divider]
          [:div.feature.content
           {:class (when (contains? active-features "3") "art-visible") :ref "3"}
           [:div.feature-story
            [:h2.feature-headline
             [:span "Make team collaboration more productive & engaging."]]
            [:p.feature-copy
             [:span.content-copy
              "Our team features are optimized to make collaborating in real-time effortless.
              Communicate and create new ideas with your teammates in one secure place."]]
            [:a.feature-link {:role "button" :title "Request early access."}
             [:span.content-copy
              "Request early access."]]]
           [:div.feature-media artwork-team]]])))))

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
           [:div.our-philosphy-wrap
            [:div.our-philosphy.content
             [:h1.philosphy-headline
              [:span.philosphy-needed "Precursor is pure prototyping."]]
             [:p.philosphy-subtext
              [:span.philosphy-needed "Real-time collaboration"]
              [:span.philosphy-excess " that makes it easy "]
              [:span.philosphy-excess " to focus on what's important"]
              [:span.philosphy-needed "."]]
             [:div.calls-to-action
              (om/build common/google-login {:source "Landing What"})
              (om/build make-button (select-keys app [:document/id]))]]]
           navigation]])))))

(defn landing [app owner]
  (reify
    om/IDisplayName (display-name [_] "Landing")
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:div.outer.landing
          {:class (when-not (get app :not-landing?) "landed")}
          (om/build the-why app)
          (om/build the-how app)
          (om/build the-what app)])))))
