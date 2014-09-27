(ns frontend.components.project.common
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [frontend.async :refer [put!]]
            [frontend.components.forms :as forms]
            [frontend.datetime :as time-utils]
            [frontend.models.plan :as plan-model]
            [frontend.models.project :as project-model]
            [frontend.routes :as routes]
            [frontend.utils.vcs-url :as vcs-url]
            [cljs-time.format :as time-format]
            [goog.string :as gstring]
            [goog.string.format]
            [om.core :as om :include-macros true]
            [sablono.core :as html :refer-macros [html]]))

(defn trial-notice [plan owner]
  (reify
    om/IRender
    (render [_]
      (let [days (plan-model/days-left-in-trial plan)
            org-name (:org_name plan)
            plan-path (routes/v1-org-settings-subpage {:org org-name :subpage "plan"})]
        (html
         [:div.alert {:class (when (plan-model/trial-over? plan) "alert-error")}
          (cond (plan-model/trial-over? plan)
                (list (gstring/format "%s's trial is over. " org-name)
                      [:a {:href plan-path} "Add a plan to continue running builds of private repositories"]
                      ".")

                (< 10 days)
                (list (gstring/format "%s is in a 2-week trial, enjoy! (or check out " org-name)
                      [:a {:href plan-path} "our plans"]
                      ").")

                (< 7 days)
                (list (gstring/format "%s's trial has %s days left. " org-name days)
                      [:a {:href plan-path} "Check out our plans"]
                      ".")

                (< 4 days)
                (list (gstring/format "%s's trial has %s days left. " org-name days)
                      [:a {:href plan-path} "Add a plan"]
                      " to keep running your builds.")

                :else
                (list (gstring/format "%s's trial expires in %s! " org-name (plan-model/pretty-trial-time plan))
                      [:a {:href plan-path} "Add a plan"]
                      " to keep running your builds."))])))))

(defn show-enable-notice [project]
  (not (:has_usable_key project)))

(defn enable-notice [project owner]
  (reify
    om/IRender
    (render [_]
      (let [project-name (vcs-url/project-name (:vcs_url project))
            project-id (project-model/id project)
            controls-ch (om/get-shared owner [:comms :controls])]
        (html
         [:div.row-fluid
          [:div.offset1.span10
           [:div.alert.alert-error
            "Project "
            project-name
            " isn't configured with a deploy key or a github user, so we may not be able to test all pushes."
            (forms/stateful-button
             [:button.btn.btn-primary
              {:data-loading-text "Adding...",
               :on-click #(put! controls-ch [:enabled-project {:project-id project-id
                                                               :project-name project-name}])}
              "Add SSH key"])]]])))))

(defn show-follow-notice [project]
  ;; followed here indicates that the user is following this project, not that the
  ;; project has followers
  (not (:followed project)))

(defn follow-notice [project owner]
  (reify
    om/IRender
    (render [_]
      (let [project-name (vcs-url/project-name (:vcs_url project))
            vcs-url (:vcs_url project)
            controls-ch (om/get-shared owner [:comms :controls])]
        (html
         [:div.row-fluid
          [:div.offset1.span10
           [:div.alert.alert-success
            (forms/stateful-button
             [:button.btn.btn-primary
              {:data-loading-text "Following...",
               :on-click #(put! controls-ch [:followed-repo {:vcs_url vcs-url}])}
              "Follow"])
            " " project-name " to add " project-name " to your sidebar and get build notifications."]]])))))
