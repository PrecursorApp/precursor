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
                                                   (google-auth/user-info-from-sub sub))]
    (cust/update! cust (utils/remove-map-nils (merge {:cust/first-name first-name
                                                      :cust/last-name last-name
                                                      :cust/birthday birthday
                                                      :cust/gender gender
                                                      :cust/occupation occupation
                                                      :google-account/avatar avatar-url}
                                                     ;; This is racy, but we're unlikely to encounter this race
                                                     ;; and we don't want to take the time to do a db.fn/cas
                                                     (when-not (:cust/name cust)
                                                       {:cust/name first-name}))))))

(defn perform-blocking-new-cust-updates [cust ring-req]
  (let [cust (crm/update-with-dribbble-username cust)]
    (utils/with-report-exceptions
      (analytics/track-signup cust ring-req))
    (utils/with-report-exceptions
      (analytics/track-user-info cust))
    (utils/with-report-exceptions
      (crm/ping-chat-with-new-user cust))))

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
                                  :cust/uuid (d/squuid)})
              user (deref (future (update-user-from-sub user)) 500 user)]
          (future (perform-blocking-new-cust-updates user ring-req))
          user)
        (catch Exception e
          (if (pcd/unique-conflict? e)
            (cust/find-by-google-sub (pcd/default-db) (:sub user-info))
            (throw e)))))))

;; Note: this is hardcoded to prcrsr.com for a good reason

(defn cust-permission [db doc cust]
  (when cust
    (cond (and (:document/creator doc)
               (= (:cust/uuid cust)
                  (:document/creator doc)))
          :owner

          (contains? (permission-model/permits db doc cust) :permission.permits/admin)
          :admin

          :else nil)))

(defn access-grant-permission [db doc access-grant]
  (when (and access-grant
             (:db/id doc)
             (= (:db/id doc)
                (:db/id (:access-grant/document-ref access-grant))))
    :read))

(defn permission-permission [db doc permission]
  (when (and permission
             (:db/id doc)
             (:permission/document-ref permission)
             (= (:db/id doc) (:db/id (:permission/document-ref permission)))
             (not (permission-model/expired? permission)))
    (cond (contains? (:permission/permits permission) :permission.permits/admin)
          :admin
          (contains? (:permission/permits permission) :permission.permits/read)
          :read
          :else nil)))

(defn team-permission [db team cust]
  (when (and team cust)
    ;; TODO: fix the permissions
    (when (contains? (permission-model/team-permits db team cust) :permission.permits/admin)
      :owner)))

;; TODO: unify these so that there is only 1 permission type
;;       Could still have multiple permissions for a doc, but want
;;       to have 1 type. Owner would automatically get the owner permission
;; TODO: this should return a :permission/permits type of thing
(defn document-permission [db doc auth]
  (or (team-permission db (:document/team doc) (:cust auth))
      (cust-permission db doc (:cust auth))
      ;; TODO: stop using access grant tokens as permissions
      ;;       Can remove once all of the tokens expire

      (access-grant-permission db doc (:access-grant auth))
      (permission-permission db doc (:permission auth))))

(def scope-heirarchy [:read :admin :owner])

(defn contains-scope? [heirarchy granted-scope requested-scope]
  (contains? (set (take (inc (.indexOf heirarchy granted-scope)) heirarchy))
             requested-scope))

;; TODO: public and have permission are different things
(defn has-document-permission? [db doc auth scope]
  (or (= :document.privacy/public (:document/privacy doc))
      (and (= :document.privacy/read-only (:document/privacy doc))
           (contains-scope? scope-heirarchy :read scope))
      (contains-scope? scope-heirarchy (document-permission db doc auth) scope)))

(defn max-document-scope [db doc auth]
  (loop [scopes (reverse scope-heirarchy)]
    (when-let [scope (first scopes)]
      (if (has-document-permission? db doc auth scope)
        scope
        (recur (next scopes))))))

(defn has-team-permission? [db team auth scope]
  (contains-scope? scope-heirarchy (team-permission db team (:cust auth)) scope))

(defn logged-in? [ring-req]
  (seq (get-in ring-req [:auth :cust])))
