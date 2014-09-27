(ns frontend.components.aside
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [frontend.async :refer [put!]]
            [frontend.components.common :as common]
            [frontend.components.shared :as shared]
            [frontend.models.build :as build-model]
            [frontend.models.project :as project-model]
            [frontend.routes :as routes]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.github :as gh-utils]
            [frontend.utils.vcs-url :as vcs-url]
            [frontend.utils.seq :refer [select-in]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]]))

(defn status-ico-name [build]
  (case (:status build)
    "running" :busy-light

    "success" :pass-light
    "fixed"   :pass-light

    "failed"   :fail-light
    "timedout" :fail-light

    "queued"      :hold-light
    "not_running" :hold-light
    "retried"     :hold-light
    "scheduled"   :hold-light

    "canceled"            :stop-light
    "no_tests"            :stop-light
    "not_run"             :stop-light
    "infrastructure_fail" :stop-light
    "killed"              :stop-light

    :none-light))

(defn sidebar-build [build {:keys [org repo branch latest?]}]
  [:a.status {:class (when latest? "latest")
       :href (routes/v1-build-path org repo (:build_num build))
       :title (str (build-model/status-words build) ": " (:build_num build))}
   (common/ico (status-ico-name build))])

(defn branch [data owner]
  (reify
    om/IDisplayName (display-name [_] "Aside Branch Activity")
    om/IRender
    (render [_]
      (let [{:keys [org repo branch-data]} data
            [name-kw branch-builds] branch-data
            display-builds (take-last 5 (sort-by :build_num (concat (:running_builds branch-builds)
                                                                    (:recent_builds branch-builds))))]
        (html
         [:li
          [:div.branch
           {:role "button"}
           [:a {:href (routes/v1-dashboard-path {:org org :repo repo :branch (name name-kw)})
                :title (utils/display-branch name-kw)}
            (-> name-kw utils/display-branch (utils/trim-middle 23))]]
          [:div.statuses {:role "button"}
           (for [build display-builds]
             (sidebar-build build {:org org :repo repo :branch (name name-kw)}))]])))))

(defn project-aside [data owner opts]
  (reify
    om/IDisplayName (display-name [_] "Aside Project Activity")
    om/IRender
    (render [_]
      (let [login (:login opts)
            project (:project data)
            controls-ch (om/get-shared owner [:comms :controls])
            settings (:settings data)
            project-id (project-model/id project)
            ;; lets us store collapse branches in localstorage without leaking info
            project-id-hash (utils/md5 project-id)
            show-all-branches? (get-in data state/show-all-branches-path)
            collapse-branches? (get-in data (state/project-branches-collapsed-path project-id-hash))
            vcs-url (:vcs_url project)
            org (vcs-url/org-name vcs-url)
            repo (vcs-url/repo-name vcs-url)
            branches-filter (if show-all-branches? identity (partial project-model/personal-branch? {:login login} project))]
        (html
         [:ul {:class (when-not collapse-branches? "open")}
          [:li
           [:div.project {:role "button"}
            [:a.toggle {:title "show/hide"
                        :on-click #(put! controls-ch [:collapse-branches-toggled {:project-id project-id
                                                                                  :project-id-hash project-id-hash}])}
             (common/ico :repo)]

            [:a.title {:href (routes/v1-project-dashboard {:org org
                                                           :repo repo})
                       :title (project-model/project-name project)}
             (project-model/project-name project)]
            (when-let [latest-master-build (last (project-model/master-builds project))]
              (sidebar-build latest-master-build {:org org :repo repo :branch (name (:default_branch project)) :latest? true}))]]
          (when-not collapse-branches?
            (for [branch-data (->> project
                                   :branches
                                   (filter branches-filter)
                                   ;; alphabetize
                                   (sort-by first))]
              (om/build branch
                        {:branch-data branch-data
                         :org org
                         :repo repo}
                        {:react-key (first branch-data)})))])))))

(defn context-menu [app owner]
  (reify
    om/IRender
    (render [_]
      (let [controls-ch (om/get-shared owner [:comms :controls])]
        (html
          [:div.aside-user {:class (when (get-in app state/user-options-shown-path)
                                     "open")}
           [:header
            [:h5 "Your Account"]
            [:a.close-menu
             {:on-click #(put! controls-ch [:user-options-toggled])}
             (common/ico :fail-light)]]
           [:div.aside-user-options
            [:a.aside-item {:href "/account"} "Settings"]
            [:a.aside-item {:on-click #(do
                                        (utils/open-modal "#inviteForm")
                                        (put! controls-ch [:user-options-toggled]))}
             "Invite a Teammate"]
            [:a.aside-item {:href "/logout"} "Logout"]]])))))

(defn activity [app owner opts]
  (reify
    om/IDisplayName (display-name [_] "Aside Activity")
    om/IRender
    (render [_]
      (let [slim-aside? (get-in app state/slim-aside-path)
            show-all-branches? (get-in app state/show-all-branches-path)
            projects (get-in app state/projects-path)
            settings (get-in app state/settings-path)
            controls-ch (om/get-shared owner [:comms :controls])]
        (html
         [:nav.aside-left-menu
          (om/build context-menu app)
          [:div.aside-activity.open
           [:div.wrapper
            [:header
             [:select {:name "toggle-all-branches"
                       :on-change #(put! controls-ch [:show-all-branches-toggled
                                                      (utils/parse-uri-bool (.. % -target -value))])
                       :value show-all-branches?}
              [:option {:value false} "Your Branch Activity"]
              [:option {:value true} "All Branch Activity" ]]
             [:div.select-arrow [:i.fa.fa-caret-down]]]
            (for [project (sort project-model/sidebar-sort projects)]
              (om/build project-aside
                        {:project project
                         :settings settings}
                        {:react-key (project-model/id project)
                         :opts {:login (:login opts)}}))]]])))))

(defn aside-nav [app owner opts]
  (reify
    om/IDisplayName (display-name [_] "Aside Nav")
    om/IDidMount
    (did-mount [_]
      (utils/tooltip ".aside-item"))
    om/IRender
    (render [_]
      (let [controls-ch (om/get-shared owner [:comms :controls])
            projects (get-in app state/projects-path)
            settings (get-in app state/settings-path)
            slim-aside? (get-in app state/slim-aside-path)]
        (html
         [:nav.aside-left-nav

          [:a.aside-item.logo  {:title "Home"
                                :data-placement "right"
                                :data-trigger "hover"
                                :href "/"}
           [:div.logomark
            (common/ico :logo)]]

          [:a.aside-item {:on-click #(put! controls-ch [:user-options-toggled])
                          :data-placement "right"
                          :data-trigger "hover"
                          :title "Account"
                          :class (when (get-in app state/user-options-shown-path)
                                  "open")}
           [:img {:src (gh-utils/make-avatar-url opts)}]
           (:login opts)]

          [:a.aside-item {:title "Documentation"
                          :data-placement "right"
                          :data-trigger "hover"
                          :href "/docs"}
           [:i.fa.fa-copy]
           [:span "Documentation"]]

          [:a.aside-item {:on-click #(put! controls-ch [:intercom-dialog-raised])
                          :title "Report Bug"
                          :data-placement "right"
                          :data-trigger "hover"

                          :data-bind "tooltip: {title: 'Report Bug', placement: 'right', trigger: 'hover'}, click: $root.raiseIntercomDialog",}
           [:i.fa.fa-bug]
           [:span "Report Bug"]]

          [:a.aside-item
           {:href "https://www.hipchat.com/gjwkHcrD5",
            :target "_blank",
            :data-placement "right"
            :data-trigger "hover"
            :title "Live Support"}
           [:i.fa.fa-comments]
           [:span "Live Support"]]

          [:a.aside-item {:href "/add-projects",
                                       :data-placement "right"
                                       :data-trigger "hover"
                                       :title "Add Projects"}
           [:i.fa.fa-plus-circle]
           [:span "Add Projects"]]

          [:a.aside-item {:data-placement "right"
                          :data-trigger "hover"
                          :title "Changelog"
                          :href "/changelog"}
           [:i.fa.fa-bell]
           [:span "Changelog"]]

          [:a.aside-item {:data-placement "right"
                          :data-trigger "hover"
                          :title "Expand"
                          :on-click #(put! controls-ch [:slim-aside-toggled])}
           (if slim-aside?
             [:i.fa.fa-long-arrow-right]
             (list
              [:i.fa.fa-long-arrow-left]
              [:span "Collapse"]))]])))))

(defn aside [app owner]
  (reify
    om/IDisplayName (display-name [_] "Aside")
    om/IRender
    (render [_]
      (let [data (select-in app [state/projects-path state/settings-path state/user-options-shown-path])
            user (get-in app state/user-path)
            login (:login user)
            avatar-url (gh-utils/make-avatar-url user)]
        (html
         [:aside.app-aside-left
          shared/invite-form ;; modal trigger is in aside-nav
          (om/build aside-nav data {:opts user})

          (om/build activity data {:opts {:login login}})])))))
