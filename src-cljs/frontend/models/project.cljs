(ns frontend.models.project
  (:require [clojure.string :refer [lower-case]]
            [frontend.utils :as utils :include-macros true]
            [goog.string :as gstring]))

(defn project-name [project]
  (subs (:vcs_url project) 19 (count (:vcs_url project))))

(defn path-for [project & [branch]]
  (str "/gh/" (project-name project)
       (when branch
         (str "/tree/" (gstring/urlEncode branch)))))

(defn settings-path [project]
  (str "/gh/" (project-name project) "/edit"))

(defn default-branch? [branch-name project]
  (= (name branch-name) (:default_branch project)))

(defn personal-branch? [user project branch-data]
  (let [[branch-name build-info] branch-data]
    (or (default-branch? branch-name project)
        (some #{(:login user)} (:pusher_logins build-info)))))

(defn branch-builds [project branch-name-kw]
  (let [build-data (get-in project [:branches branch-name-kw])]
    (sort-by :build_num (concat (:running_builds build-data)
                                (:recent_builds build-data)))))

(defn master-builds
  "Returns branch builds for the project's default branch (usually master)"
  [project]
  (branch-builds project (keyword (:default_branch project))))

(def hipchat-keys [:hipchat_room :hipchat_api_token :hipchat_notify :hipchat_notify_prefs])
(def slack-keys [:slack_channel :slack_subdomain :slack_api_token :slack_notify_prefs :slack_webhook_url])
(def hall-keys [:hall_room_api_token :hall_notify_prefs])
(def campfire-keys [:campfire_room :campfire_token :campfire_subdomain :campfire_notify_prefs])
(def flowdock-keys [:flowdock_api_token])
(def irc-keys [:irc_server :irc_channel :irc_keyword :irc_username :irc_password :irc_notify_prefs])

(def notification-keys (concat hipchat-keys slack-keys hall-keys campfire-keys flowdock-keys irc-keys))

(defn notification-settings [project]
  (select-keys project notification-keys))

(defn last-master-build
  "Gets the last finished master build on the branch"
  [project]
  (first (get-in project [:branches (keyword (:default_branch project)) :recent_builds])))

(defn sidebar-sort [l, r]
  (let [l-last-build (last-master-build l)
        r-last-build (last-master-build r)]
    (cond (and l-last-build r-last-build)
          (compare (:build_num r-last-build)
                   (:build_num l-last-build))

          l-last-build -1
          r-last-build 1
          :else (compare (lower-case (:vcs_url l)) (lower-case (:vcs_url r))))))

(defn id [project]
  (:vcs_url project))
