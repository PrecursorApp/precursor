(ns frontend.components.clip-viewer
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
            [frontend.sente :as sente]
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
    om/IDisplayName (display-name [_] "Clip Viewer Signup Prompt")
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:section.menu-view
          [:div.content
           [:h2.make
            "Keep track of everything you copy and paste."]
           [:p.make
            "If you sign in with Google we keep track of everything you copy."]
           [:div.calls-to-action.make
            (om/build common/google-login {:source "Clip viewer signup"})]]])))))

(defn clips-list [clips owner]
  (reify
    om/IDisplayName (display-name [_] "Clip Viewer clips List")
    om/IDidMount (did-mount [_] (fdb/watch-doc-name-changes owner))
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)]
        (html
         [:div
          (for [clip (reverse (sort-by #(some-> % :clip/uuid d/squuid-time-millis) clips))]
            (html
             [:div {:style {:display "flex"
                            :align-items "center"
                            :flex-flow "row wrap"}}
              [:a.make {:style {:flex "1 100%"}
                        :role "button"
                        :on-click #(cast! :clip-pasted clip)}
               [:img {:src (:clip/s3-url clip)}]

               [:i.loading-ellipses
                [:i "."]
                [:i "."]
                [:i "."]]]
              (if (:clip/important? clip)
                [:a {:role "button"
                     :on-click #(cast! :unimportant-clip-marked {:clip/uuid (:clip/uuid clip)})}
                 "Mark Unimportant"]
                [:a {:role "button"
                     :on-click #(cast! :important-clip-marked {:clip/uuid (:clip/uuid clip)})}
                 "Mark important"])
              [:span " "]
              [:a.make {:role "button"
                        :on-click #(cast! :delete-clip-clicked {:clip/uuid (:clip/uuid clip)})}
               (str "Delete " (:clip/uuid clip))]]))])))))

(defn clip-viewer* [app owner]
  (reify
    om/IDisplayName (display-name [_] "Clip Viewer*")
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            clips (get-in app [:cust :cust/clips])]
        (html
         [:section.menu-view {:class (when (nil? clips) "loading")}
          (om/build clips-list clips)])))))

;; Four states
;; 1. Logged out
;; 2. Loading
;; 3. No docs
;; 4. Docs!

(defn clip-viewer [app owner]
  (reify
    om/IDisplayName (display-name [_] "Doc Viewer")
    om/IRender
    (render [_]
      (if (:cust app)
        (om/build clip-viewer* app) ;; states 2, 3, 4
        (om/build signup-prompt app) ;; state 1
        ))))
