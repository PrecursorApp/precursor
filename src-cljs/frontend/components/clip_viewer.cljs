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
      (html
       [:div.content
        (for [clip (reverse (sort-by :db/id clips))]
          (html
           [:a.recent-doc.make {:href (:clip/s3-url clip)}
            [:object {:data (:clip/s3-url clip) :type "image/svg+xml"}]

            [:i.loading-ellipses
             [:i "."]
             [:i "."]
             [:i "."]]]))]))))

(defn clip-viewer* [app owner]
  (reify
    om/IDisplayName (display-name [_] "Clip Viewer*")
    om/IDidMount
    (did-mount [_]
      (sente/send-msg (om/get-shared owner :sente)
                      [:cust/fetch-clips]
                      10000
                      (fn [reply]
                        (utils/inspect reply)
                        (when (om/mounted? owner)
                          (om/set-state! owner :clips (:clips reply))))))
    om/IRenderState
    (render-state [_ {:keys [clips]}]
      (let [cast! (om/get-shared owner :cast!)]
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
