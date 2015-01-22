(ns pc.auth
  (:require [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [crypto.equality :as crypto]
            [datomic.api :as d]
            [org.httpkit.client :as http]
            [pc.analytics :as analytics]
            [pc.auth.google :as google-auth]
            [pc.crm :as crm]
            [pc.models.cust :as cust]
            [pc.models.permission :as permission-model]
            [pc.datomic :as pcd]
            [pc.profile :as profile]
            [pc.utils :as utils])
  (:import java.util.UUID))

(defn update-user-from-sub [cust]
  (let [sub (:google-account/sub cust)
        {:keys [first-name last-name gender
                avatar-url birthday occupation]} (utils/with-report-exceptions
                                                   (google-auth/user-info-from-sub sub))
        cust (-> cust
               (cust/update! (utils/remove-map-nils {:cust/first-name first-name
                                                     :cust/last-name last-name
                                                     :cust/birthday birthday
                                                     :cust/gender gender
                                                     :cust/occupation occupation
                                                     :google-account/avatar avatar-url}))
               crm/update-with-dribbble-username)]
    (utils/with-report-exceptions
      (analytics/track-user-info cust))
    (when (profile/prod?)
      (utils/with-report-exceptions
        (crm/ping-chat-with-new-user cust)))
    cust))

(defn cust-from-google-oauth-code [code ring-req]
  {:post [(string? (:google-account/sub %))]} ;; should never break, but just in case...
  (let [user-info (google-auth/user-info-from-code code)
        db (pcd/default-db)]
    (if-let [cust (cust/find-by-google-sub db (:sub user-info))]
      (do
        (analytics/track-login cust)
        (cust/update! cust (merge {:cust/email (:email user-info)
                                   :cust/verified-email (:email_verified user-info)}
                                  (when-not (:cust/http-session-key cust)
                                    {:cust/http-session-key (UUID/randomUUID)}))))
      (try
        (let [user (cust/create! {:cust/email (:email user-info)
                                  :cust/verified-email (:email_verified user-info)
                                  :cust/http-session-key (UUID/randomUUID)
                                  :google-account/sub (:sub user-info)
                                  :cust/uuid (UUID/randomUUID)})]
          (analytics/track-signup user ring-req)
          (future (utils/with-report-exceptions (update-user-from-sub user)))
          user)
        (catch Exception e
          (if (pcd/unique-conflict? e)
            (cust/find-by-google-sub (pcd/default-db) (:sub user-info))
            (throw e)))))))

(def prcrsr-bot-email "prcrsr-bot@prcrsr.com")
(defn prcrsr-bot-uuid [db]
  (ffirst (d/q '{:find [?u]
                 :in [$ ?e]
                 :where [[?t :cust/email ?e]
                         [?t :cust/uuid ?u]]}
               db prcrsr-bot-email)))

(defn cust-permission [db doc cust]
  (when cust
    (cond (and (:document/creator doc)
               (crypto/eq? (str (:cust/uuid cust))
                           (str (:document/creator doc))))
          :owner

          (contains? (permission-model/permits db doc cust) :permission.permits/admin)
          :admin

          :else nil)))

(defn access-grant-permission [db doc access-grant]
  (when (and access-grant
             (:db/id doc)
             (= (:db/id doc)
                (:access-grant/document access-grant)))
    :read))

(defn permission-permission [db doc permission]
  (when (and permission
             (:db/id doc)
             (:permission/document permission)
             (= (:db/id doc) (:permission/document permission))
             (not (permission-model/expired? permission))
             (cond (contains? (:permission/permits permission) :permission.permits/admin)
                   :admin
                   (contains? (:permission/permits permission) :permission.permits/read)
                   :read
                   :else nil))))

;; TODO: unify these so that there is only 1 permission type
;;       Could still have multiple permissions for a doc, but want
;;       to have 1 type. Owner would automatically get the owner permission
;; TODO: this should return a :permission/permits type of thing
(defn document-permission [db doc auth]
  (or (cust-permission db doc (:cust auth))
      (access-grant-permission db doc (:access-grant auth))
      (permission-permission db doc (:permission auth))))

(def scope-heirarchy [:read :admin :owner])

(defn contains-scope? [heirarchy granted-scope requested-scope]
  (contains? (set (take (inc (.indexOf heirarchy granted-scope)) heirarchy))
             requested-scope))

;; TODO: public and have permission are different things
(defn has-document-permission? [db doc auth scope]
  (or (= :document.privacy/public (:document/privacy doc))
      (contains-scope? scope-heirarchy (document-permission db doc auth) scope)))

(defn logged-in? [ring-req]
  (seq (get-in ring-req [:auth :cust])))
