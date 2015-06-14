(ns pc.analytics
  (:require [clj-time.coerce]
            [clj-time.format]
            [pc.http.admin.urls :as admin-urls]
            [pc.mailchimp :as mailchimp]
            [pc.mixpanel :as mixpanel]
            [pc.models.cust :as cust-model]
            [pc.datomic :as pcd]))

(defn track-signup [cust ring-req]
  (mixpanel/alias (mixpanel/distinct-id-from-cookie ring-req) (:cust/uuid cust))
  (mixpanel/track "$signup" (:cust/uuid cust)))

(defn track-user-info [cust]
  (let [created-at (cust-model/created-at (pcd/default-db) cust)]
    (mixpanel/engage (:cust/uuid cust) {:$ip 0
                                        :$set {:$first_name (:cust/first-name cust)
                                               :$last_name (:cust/last-name cust)
                                               :$created (-> created-at
                                                           clj-time.coerce/from-date
                                                           mixpanel/->full-mixpanel-date)
                                               :$email (:cust/email cust)
                                               :gender (:cust/gender cust)
                                               :birthday (some-> cust
                                                           :cust/birthday
                                                           clj-time.coerce/from-date
                                                           mixpanel/->mixpanel-date)
                                               :verified_email (:cust/verified-email cust)
                                               ;; want ":_" to push it to top of list
                                               ":_admin_url" (admin-urls/cust-info-from-cust cust)
                                               :occupation (:cust/occupation cust)
                                               :precursor-contact (first (shuffle cust-model/admin-emails))}})
    (mailchimp/maybe-list-subscribe cust)))

(defn track-create-team [team]
  (mixpanel/engage (:cust/uuid (:team/creator team))
                   {:$set {:team_trial_expires (->> team
                                                 :team/plan
                                                 :plan/trial-end
                                                 clj-time.coerce/from-date
                                                 (clj-time.format/unparse (clj-time.format/formatters :date-time-no-ms)))}})
  (mixpanel/track "Created team" (:cust/uuid (:team/creator team))
                  :subdomain (:team/subdomain team)))

(defn track-create-plan [team]
  (mixpanel/engage (:cust/uuid (:team/creator team)) {:$set {:created_plan true}})
  (mixpanel/track "Created plan" (:cust/uuid (:team/creator team))
                  :subdomain (:team/subdomain team)))

(defn track-login [cust]
  (mixpanel/track "Login" (:cust/uuid cust)))

(defn track-signup-clicked [ring-req]
  (mixpanel/track "Signup Clicked"
                  (mixpanel/distinct-id-from-cookie ring-req)
                  :source (get-in ring-req [:params "source"])))
