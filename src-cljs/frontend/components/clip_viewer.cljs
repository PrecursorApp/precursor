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
         [:div.content
          [:h2.make "Paste clips into your doc. "]
          [:p.make "Add new clips to the list by copying selected shapes on the canvas with Cmd+C. "
                   "Star clips to pin them to the top. "]
          [:div.clips
           (for [clip clips]
             (html
              [:div.clip.make
               [:a.clip-preview {:role "button"
                                 :on-click #(cast! :clip-pasted clip)}
                [:img.clip-thumbnail {:src (:clip/s3-url clip)}]]
               (if (:clip/important? clip)

                 [:div.clip-option.stuck
                  [:a.clip-button {:role "button"
                                   :on-click #(do (cast! :unimportant-clip-marked {:clip/uuid (:clip/uuid clip)})
                                                (utils/stop-event %))}
                   (common/icon :starred)]]

                 [:div.clip-option
                  [:a.clip-button {:role "button"
                                   :on-click #(do (cast! :important-clip-marked {:clip/uuid (:clip/uuid clip)})
                                                (utils/stop-event %))}
                   (common/icon :star)]])
               [:div.clip-option
                [:a.clip-button {:role "button"
                                 :on-click #(do (cast! :delete-clip-clicked {:clip/uuid (:clip/uuid clip)})
                                              (utils/stop-event %))}
                 (common/icon :times)]]]))]])))))

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
