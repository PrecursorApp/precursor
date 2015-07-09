(ns frontend.components.doc-viewer
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [cljs-time.core :as time]
            [datascript :as d]
            [frontend.async :refer [put!]]
            [frontend.auth :as auth]
            [frontend.components.common :as common]
            [frontend.datascript :as ds]
            [frontend.datetime :as datetime]
            [frontend.db :as fdb]
            [frontend.state :as state]
            [frontend.urls :as urls]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.date :refer (date->bucket)]
            [goog.date]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [sablono.core :refer (html)])
  (:import [goog.ui IdGenerator]))

(defn signup-prompt [app owner]
  (reify
    om/IDisplayName (display-name [_] "Doc Viewer Signup Prompt")
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:section.menu-view
          [:div.content
           [:h2.make
            "Keep track of all the ideas you make."]
           [:p.make
            "Everyone's ideas made with Precursor save automatically.
            And if you sign in with Google we'll even keep track of which ones are yours."]
           [:div.calls-to-action.make
            (om/build common/google-login {:source "Sharing Signup Menu"})]]])))))

(defn docs-list [docs owner]
  (reify
    om/IDisplayName (display-name [_] "Doc Viewer Docs List")
    om/IDidMount (did-mount [_] (fdb/watch-doc-name-changes owner))
    om/IRender
    (render [_]
      (html
       [:div.recent-docs
        (for [[time-bucket bucket-docs]
              (reverse (sort-by #(:last-updated-instant (first (last %)))
                                (group-by #(date->bucket (:last-updated-instant %)) docs)))]
          (list*
            (html [:div.recent-docs-heading.divider-small.make time-bucket])

            (for [doc bucket-docs]
              (html
                [:a.recent-doc.make {:href (str (urls/doc-path doc) "/doc-viewer")
                                     :on-touch-end #(do
                                                      (.preventDefault %)
                                                      (put! (om/get-shared owner [:comms :nav]) [:navigate! {:path (str (urls/doc-path doc))}]))}
                 [:img.recent-doc-thumb {:src (urls/doc-svg-path doc)}]

                 (common/icon :loading)

                 [:span.recent-doc-info
                  [:span.recent-doc-title {:title (str "/" (urls/urlify-doc-name (:document/name doc)) "-" (:db/id doc))}
                   (str (:document/name doc "Untitled")
                        (when (= "Untitled" (:document/name doc))
                          (str " (" (:db/id doc) ")")))]
                  (when (:last-updated-instant doc)
                    [:span.recent-doc-timestamp {:title (str "Last edited " (datetime/calendar-date (:last-updated-instant doc)))}
                     (str " " (datetime/month-day-short (:last-updated-instant doc)))])]]))))]))))

(defn dummy-docs [current-doc-id doc-count]
  (repeat doc-count {:db/id current-doc-id
                     :last-update-instant (js/Date.)}))

(defn doc-viewer* [app owner {:keys [docs-path] :as opts}]
  (reify
    om/IDisplayName (display-name [_] "Doc Viewer*")
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            app-docs (get-in app docs-path)
            all-docs (cond
                       (nil? app-docs)
                       (dummy-docs (:document/id app) 5) ;; loading state

                       (empty? app-docs) (dummy-docs (:document/id app) 1) ;; empty state

                       :else
                       (->> app-docs
                         (filter :last-updated-instant)
                         (sort-by :last-updated-instant)
                         (reverse)))
            display-docs (take 100 all-docs)
            filtered-docs (if (seq (om/get-state owner :doc-filter))
                            (take 100 (let [filter-text (str/lower-case (om/get-state owner :doc-filter))]
                                        (filter #(not= -1 (.indexOf (str/lower-case (:document/name %))
                                                                    filter-text))
                                                all-docs)))
                            display-docs)
            searching? (seq (om/get-state owner :doc-filter))]
        (html
         [:section.menu-view.menu-docs {:class (str (when (nil? app-docs) " loading ")
                                                    (when searching? " searching "))}
           (when (seq display-docs)
             (list
             [:div.content
               [:div.adaptive.make
                [:input {:type "text"
                         :tab-index "1"
                         :value (or (om/get-state owner :doc-filter) "")
                         :required "true"
                         :onChange #(om/set-state! owner :doc-filter (.. % -target -value))}]
                [:label {:data-placeholder "Searching for a doc?"
                         :data-label (str (count filtered-docs) " of your " (count all-docs) " docs match")}]]
               (when (and searching?
                          (zero? (count filtered-docs)))
                 [:p.make
                  [:span (str "We can't find a doc matching "
                              "\""
                              (or (om/get-state owner :doc-filter) "")
                              "\".")]])]

               (om/build docs-list filtered-docs)))])))))

;; Four states
;; 1. Logged out
;; 2. Loading
;; 3. No docs
;; 4. Docs!

(defn doc-viewer [app owner]
  (reify
    om/IDisplayName (display-name [_] "Doc Viewer")
    om/IRender
    (render [_]
      (if (:cust app)
        (om/build doc-viewer* app {:opts {:docs-path [:cust :touched-docs]}}) ;; states 2, 3, 4
        (om/build signup-prompt app) ;; state 1
        ))))

(defn team-doc-viewer [app owner]
  (reify
    om/IDisplayName (display-name [_] "Team Doc Viewer")
    om/IRender
    (render [_]
      (om/build doc-viewer* app {:opts {:docs-path [:team :recent-docs]}}))))
