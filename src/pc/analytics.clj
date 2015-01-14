(ns pc.analytics
  (:require [clj-time.coerce]
            [pc.mailchimp :as mailchimp]
            [pc.mixpanel :as mixpanel]
            [pc.models.cust :as cust-model]
            [pc.datomic :as pcd]))

(defn track-signup [cust ring-req]
  (mixpanel/alias (mixpanel/distinct-id-from-cookie ring-req) (:cust/uuid cust))
  (mixpanel/track "$signup" (:cust/uuid cust)))

(defn track-user-info [cust]
  (let [created-at (cust-model/created-at (pcd/default-db) cust)]
    (mixpanel/engage (:cust/uuid cust) {:$set {:$first_name (:cust/first-name cust)
                                               :$last_name (:cust/last-name cust)
                                               :$created (-> created-at
                                                             clj-time.coerce/from-date
                                                             mixpanel/->mixpanel-date)
                                               :$email (:cust/email cust)
                                               :gender (:cust/gender cust)
                                               :birthday (some-> cust
                                                                 :cust/birthday
                                                                 clj-time.coerce/from-date
                                                                 mixpanel/->mixpanel-date)
                                               :verified_email (:cust/verified-email cust)
                                               :occupation (:cust/occupation cust)}})
    (mailchimp/maybe-list-subscribe cust)))

(defn track-login [cust]
  (mixpanel/track "Login" (:cust/uuid cust)))
