(ns frontend.components.errors
  (:require [frontend.state :as state]
            [frontend.async :refer [put!]]
            [frontend.components.common :as common]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.github :as gh-utils]
            [om.core :as om :include-macros true])
  (:require-macros [frontend.utils :refer [html]]))

(defn error-page [app owner]
  (reify
    om/IRender
    (render [_]
      (let [status (get-in app [:navigation-data :status])
            logged-in? (get-in app state/user-path)
            orig-nav-point (get-in app [:original-navigation-point])
            _ (utils/mlog "error-page render with orig-nav-point " orig-nav-point " and logged-in? " (boolean logged-in?))
            maybe-login-page? (some #{orig-nav-point} [:dashboard :build])
            controls-ch (om/get-shared owner [:comms :controls])]
        (html
         [:div.page.error
          [:div.banner
           [:div.container
            [:h1 status]
            [:h3 (str (condp = status
                        401 "Login required"
                        404 "Page not found"
                        500 "Internal server error"
                        "Something unexpected happened"))]]]
          [:div.container
           (condp = status
             401 [:p
                  [:b [:a {:href (gh-utils/auth-url)
                           :on-click #(put! controls-ch [:track-external-link-clicked
                                                         {:event "Auth GitHub"
                                                          :properties {:source "401"
                                                                       :url js/window.location.pathname}
                                                          :path (gh-utils/auth-url)}])}
                       "Login here"]]
                  " to view this page"]
             404 (if (and (not logged-in?) maybe-login-page?)
                   [:div
                    [:p "We're sorry; either that page doesn't exist or you need to be logged in to view it."]
                    [:p [:b [:a {:href (gh-utils/auth-url)} "Login here"] " to view this page with your GitHub permissions."]]]
                   [:p "We're sorry, but that page doesn't exist."])
             500 [:p "We're sorry, but something broke"]
             "Something completely unexpected happened")]])))))
