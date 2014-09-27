(ns frontend.components.build
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [frontend.async :refer [put!]]
            [frontend.datetime :as datetime]
            [frontend.models.container :as container-model]
            [frontend.models.build :as build-model]
            [frontend.models.plan :as plan-model]
            [frontend.models.project :as project-model]
            [frontend.components.build-config :as build-config]
            [frontend.components.build-head :as build-head]
            [frontend.components.build-invites :as build-invites]
            [frontend.components.build-steps :as build-steps]
            [frontend.components.common :as common]
            [frontend.components.project.common :as project-common]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.github :as gh-utils]
            [frontend.utils.vcs-url :as vcs-url]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [sablono.core :as html :refer-macros [html]])
    (:require-macros [frontend.utils :refer [html]]))

(defn report-error [build controls-ch]
  (let [build-id (build-model/id build)
        build-url (:build_url build)]
    (when (:failed build)
      [:div.alert.alert-danger
       (if-not (:infrastructure_fail build)
         [:div.alert-wrap
          "Error! "
          [:a {:href "/docs/troubleshooting"}
           "Check out common problems "]
          "or "
          [:a {:title "Report an error in how Circle ran this build"
               :on-click #(put! controls-ch [:report-build-clicked {:build-url build-url}])}
           "report this issue"]
          " and we'll investigate."]

         [:div
          "Looks like we had a bug in our infrastructure, or that of our providers (generally "
          [:a {:href "https://status.github.com/"} "GitHub"]
          " or "
          [:a {:href "https://status.aws.amazon.com/"} "AWS"]
          ") We should have automatically retried this build. We've been alerted of"
          " the issue and are almost certainly looking into it, please "
          (common/contact-us-inner controls-ch)
          " if you're interested in the cause of the problem."])])))

(defn container-pill [{:keys [container current-container-id build-running?]} owner]
  (reify
    om/IRender
    (render [_]
      (html
       (let [container-id (container-model/id container)
             controls-ch (om/get-shared owner [:comms :controls])
             status (container-model/status container build-running?)]
        [:a.container-selector
         {:on-click #(put! controls-ch [:container-selected {:container-id container-id}])
          :role "button"
          :class (concat (container-model/status->classes status)
                         (when (= container-id current-container-id) ["active"]))}
         (str (:index container))
         (case status
           :failed (common/ico :fail-light)
           :success (common/ico :pass-light)
           :canceled (common/ico :fail-light)
           :running (common/ico :logo-light)
           :waiting (common/ico :none-light)
           nil)])))))

(defn container-pills [data owner]
  (reify
    om/IRender
    (render [_]
      (let [container-data (:container-data data)
            build-running? (:build-running? data)
            {:keys [containers current-container-id]} container-data
            controls-ch (om/get-shared owner [:comms :controls])
            hide-pills? (or (>= 1 (count containers))
                            (empty? (remove :filler-action (mapcat :actions containers))))]
        (html
         [:div.containers (when hide-pills? {:style {:display "none"}})
          [:div.container-list
           (for [container containers]
             (om/build container-pill
                       {:container container
                        :build-running? build-running?
                        :current-container-id current-container-id}
                       {:react-key (:index container)}))]])))))

(defn show-trial-notice? [project plan]
  (and (not (get-in project [:feature_flags :oss]))
       (plan-model/trial? plan)
       (plan-model/trial-over? plan)
       (> 4 (plan-model/days-left-in-trial plan))))

(defn notices [data owner]
  (reify
    om/IRender
    (render [_]
      (html
       (let [build-data (:build-data data)
             project-data (:project-data data)
             plan (:plan project-data)
             project (:project project-data)
             build (:build build-data)
             controls-ch (om/get-shared owner [:comms :controls])]
         [:div.row-fluid
          [:div.offset1.span10
           [:div (common/messages (set (:messages build)))]
           (when (empty? (:messages build))
             [:div (report-error build controls-ch)])

           (when (and plan (show-trial-notice? project plan))
             (om/build project-common/trial-notice plan))

           (when (and project (project-common/show-enable-notice project))
             (om/build project-common/enable-notice project))

           (when (and project (project-common/show-follow-notice project))
             (om/build project-common/follow-notice project))

           (when (build-model/display-build-invite build)
             (om/build build-invites/build-invites
                       (:invite-data build-data)
                       {:opts {:project-name (vcs-url/project-name (:vcs_url build))}}))

           (when (and (build-model/config-errors? build)
                      (not (:dismiss-config-errors build-data)))
             (om/build build-config/config-errors build))]])))))

(defn build [data owner]
  (reify
    om/IRender
    (render [_]
      (let [build (get-in data state/build-path)
            build-data (get-in data state/build-data-path)
            container-data (get-in data state/container-data-path)
            project-data (get-in data state/project-data-path)
            user (get-in data state/user-path)
            controls-ch (om/get-shared owner [:comms :controls])]
        (html
         [:div#build-log-container
          (if-not build
           [:div
             (om/build common/flashes (get-in data state/error-message-path))
             [:div.loading-spinner-big common/spinner]]

            [:div
             (om/build build-head/build-head {:build-data (dissoc build-data :container-data)
                                              :project-data project-data
                                              :user user
                                              :scopes (get-in data state/project-scopes-path)})
             (om/build common/flashes (get-in data state/error-message-path))
             (om/build notices {:build-data (dissoc build-data :container-data)
                                :project-data project-data})
             (om/build container-pills {:container-data container-data
                                        :build-running? (build-model/running? build)})
             (om/build build-steps/container-build-steps container-data)

             (when (< 1 (count (:steps build)))
               [:div (common/messages (:messages build))])])])))))
