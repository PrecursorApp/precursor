(ns frontend.components.doc-viewer
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [cljs-time.core :as time]
            [datascript :as d]
            [frontend.async :refer [put!]]
            [frontend.auth :as auth]
            [frontend.components.common :as common]
            [frontend.datascript :as ds]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.date :refer (date->bucket)]
            [goog.date]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]])
  (:import [goog.ui IdGenerator]))

(defn signup-prompt [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:div.menu-view {:class (str "menu-prompt-" "username")}
          [:div.menu-view-frame
           [:article
            [:h2 "Remember that one idea?"]
            [:p "Neither do we—well, not yet at least.
                Sign up and we'll remember your ideas for you.
                Never lose a great idea again!"]
            [:a.menu-button {:href (auth/auth-url)
                             :role "button"
                             :on-click #(do
                                          (.preventDefault %)
                                          (cast! :track-external-link-clicked
                                                 {:path (auth/auth-url)
                                                  :event "Signup Clicked"
                                                  :properties {:source "your-docs-overlay"}}))}
             "Sign Up"]]]])))))

(defn docs-list [docs owner]
  (reify
    om/IRender
    (render [_]
      (html
       [:div
        (for [[time-bucket bucket-docs]
              (reverse (sort-by #(:last-updated-instant (first (last %)))
                                (group-by #(date->bucket (:last-updated-instant %)) docs)))]
          (list*
           (html [:div.recent-time-group
                  [:h2 time-bucket]])

           (for [doc bucket-docs]
             (html
              [:div.recent-doc
               [:a.recent-doc-thumb {:href (str "/document/" (:db/id doc))}
                [:img {:src (str "/document/" (:db/id doc) ".svg")}]
                [:img.loading-image]
                [:i.loading-ellipses [:i "."] [:i "."] [:i "."]]]
               [:div.recent-doc-title
                [:a {:href (str "/document/" (:db/id doc))}
                 (str (:db/id doc))]]]))))]))))

(defn dummy-docs [current-doc-id doc-count]
  (repeat doc-count {:db/id current-doc-id
                     :last-update-instant (js/Date.)}))

(defn doc-viewer* [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            touched-docs (get-in app [:cust :touched-docs])
            ;; Not showing created for now, since we haven't been storing that until recently
            created-docs (get-in app [:cust :created-docs])
            docs (cond
                  (nil? touched-docs)
                  (dummy-docs (:document/id app) 5) ;; loading state
                  (empty? touched-docs) (dummy-docs (:document/id app) 1) ;; empty state
                  :else
                  (->> touched-docs
                       (filter :last-updated-instant)
                       (sort-by :last-updated-instant)
                       (reverse)
                       (take 100)))]
        (html
         [:div.menu-view
          [:div.menu-view-frame {:class (when (nil? touched-docs)
                                              "loading")}
           [:article
            (if (seq docs)
              (om/build docs-list docs))]]])))))

;; Four states
;; 1. Logged out
;; 2. Loading
;; 3. No docs
;; 4. Docs!

(defn doc-viewer [app owner]
  (reify
    om/IRender
    (render [_]
      (if (:cust app)
        (om/build doc-viewer* app) ;; states 2, 3, 4
        (om/build signup-prompt app) ;; state 1
        ))))
