(ns frontend.analytics
  (:require [frontend.analytics.adroll :as adroll]
            [frontend.analytics.google :as google]
            [frontend.analytics.marketo :as marketo]
            [frontend.analytics.mixpanel :as mixpanel]
            [frontend.analytics.perfect-audience :as pa]
            [frontend.analytics.rollbar :as rollbar]
            [frontend.analytics.twitter :as twitter]
            [frontend.analytics.facebook :as facebook]         
            [frontend.models.build :as build-model]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.vcs-url :as vcs-url]
            [goog.style]))


(defn init-user [login]
  (utils/swallow-errors
   (mixpanel/init-user login)
   (rollbar/init-user login)))

(defn track-dashboard []
  (mixpanel/track "Dashboard")
  (google/track-pageview "/dashboard"))

(defn track-homepage []
  (utils/swallow-errors
   (mixpanel/track "Outer Home Page" {"window height" (.-innerHeight js/window)})
   (google/track-pageview "/homepage")))

(defn track-org-settings [org-name]
  (mixpanel/track "View Org" {:username org-name}))

(defn track-build [build]
  (mixpanel/track "View Build" (merge {:running (build-model/running? build)
                                       :build-num (:build_num build)
                                       :vcs-url (vcs-url/project-name (:vcs_url build))
                                       :oss (boolean (:oss build))
                                       :outcome (:outcome build)}
                                      (when (:stop_time build)
                                        {:elapsed_hours (/ (- (.getTime (js/Date.))
                                                              (.getTime (js/Date. (:stop_time build))))
                                                           1000 60 60)}))))

(defn track-path [path]
  (mixpanel/track-pageview path)
  (google/push path))

(defn track-page [page & [props]]
  (mixpanel/track page props))

(defn track-pricing []
  (mixpanel/register-once {:view-pricing true}))

(defn track-invited-by [invited-by]
  (mixpanel/register-once {:invited_by invited-by}))

(defn track-save-containers []
  (mixpanel/track "Save Containers"))

(defn track-save-orgs []
  (mixpanel/track "Save Organizations"))

(defn track-extend-trial []
  (mixpanel/track "Extend trial"))

(defn track-collapse-nav []
  (mixpanel/track "aside_nav_collapsed"))

(defn track-expand-nav []
  (mixpanel/track "aside_nav_expanded"))

(defn track-signup []
  (utils/swallow-errors
   (twitter/track-signup)
   (facebook/track-signup)
   ((aget js/window "track_signup_conversion"))))

(defn track-payer [login]
  (mixpanel/track "Paid")
  (pa/track "payer" {:orderId login})
  (twitter/track-payer)
  (adroll/record-payer))

(defn track-trigger-build [build & {:keys [clear-cache? ssh?] :as extra}]
  (mixpanel/track "Trigger Build" (merge {:vcs-url (vcs-url/project-name (:vcs_url build))
                                          :build-num (:build_num build)
                                          :retry? true}
                                         extra)))

(defn track-follow-project []
  (google/track-event "Projects" "Add"))

(defn track-unfollow-project []
  (google/track-event "Projects" "Remove"))

(defn track-follow-repo []
  (google/track-event "Repos" "Add"))

(defn track-unfollow-repo []
  (google/track-event "Repos" "Remove"))

(defn track-message [message]
  (mixpanel/track-message message))
