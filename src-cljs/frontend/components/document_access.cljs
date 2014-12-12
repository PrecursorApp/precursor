(ns frontend.components.document-access
  (:require [clojure.string :as str]
            [frontend.analytics :as analytics]
            [frontend.async :refer [put!]]
            [frontend.auth :as auth]
            [frontend.components.common :as common]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]])
  (:import [goog.ui IdGenerator]))

(defn permissions-overlay [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            doc-id (:document/id app)]
        (html
         [:div.menu-view
          [:div.menu-view-frame
           [:article
            [:h2 "This document is private"]
            (if (:cust app)
              (list
               [:p "Let the owner know you want to collaborate."]
               [:a.menu-button {:on-click #(cast! :permission-requested {:doc-id doc-id})
                                :role "button"}
                "Request access"])
              (list
               [:p "To check if you have access or to request access"]
               [:a.menu-button {:href (auth/auth-url)
                                :on-click #(do
                                             (.preventDefault %)
                                             (cast! :track-external-link-clicked
                                                    {:path (auth/auth-url)
                                                     :event "Signup Clicked"
                                                     :properties {:source "permissions-overlay"}}))
                                :role "button"}
                "Sign In"]))
            [:p "Anything you draw on this document will only be visible to you. "]
            [:p
             "If you want a document of your own, you can "
             [:a {:href "/" :target "_self"} "create your own document"]
             ". "]]]])))))
