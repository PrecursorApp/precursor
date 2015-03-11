(ns pc.models.permission
  (:require [clj-time.coerce]
            [clj-time.core :as time]
            [crypto.random]
            [datomic.api :refer [db q] :as d]
            [pc.datomic :as pcd]
            [pc.datomic.web-peer :as web-peer]
            [pc.utils :as utils])
  (:import java.util.UUID))

(defn permits [db doc cust]
  (set (map first (d/q '{:find [?permits]
                         :in [$ ?db-id ?cust-id]
                         :where [[?t :permission/document-ref ?db-id]
                                 [?t :permission/cust-ref ?cust-id]
                                 [?t :permission/permits ?permit-id]
                                 [?permit-id _ ?permits]]}
                       db (:db/id doc) (:db/id cust)))))

(defn team-permits [db team cust]
  (set (map first (d/q '{:find [?permits]
                         :in [$ ?team-id ?cust-id]
                         :where [[?t :permission/team ?team-id]
                                 [?t :permission/cust-ref ?cust-id]
                                 [?t :permission/permits ?permit-id]
                                 [?permit-id _ ?permits]]}
                       db (:db/id team) (:db/id cust)))))

(defn grant-permit [doc granter cust permit annotations]
  (let [txid (d/tempid :db.part/tx)
        temp-id (d/tempid :db.part/user)]
    @(d/transact (pcd/conn)
                 [(assoc annotations :db/id txid)
                  (web-peer/server-frontend-id temp-id (:db/id doc))
                  {:db/id temp-id
                   :permission/permits permit
                   :permission/document-ref (:db/id doc)
                   :permission/cust-ref (:db/id cust)
                   :permission/grant-date (java.util.Date.)
                   ;;; XXX need to check sent-email in pc.email to guard against multiple txes!
                   :needs-email :email/permission-granted
                   :permission/granter-ref (:db/id granter)
                   :permission/doc-cust (UUID. (:db/id doc) (:db/id cust))}])))

(defn grant-team-permit [team granter cust permit annotations]
  (let [txid (d/tempid :db.part/tx)
        temp-id (d/tempid :db.part/user)]
    @(d/transact (pcd/conn)
                 [(assoc annotations :db/id txid)
                  (web-peer/server-frontend-id temp-id (:db/id team))
                  {:db/id temp-id
                   :permission/permits permit
                   :permission/team (:db/id team)
                   :permission/cust-ref (:db/id cust)
                   :permission/grant-date (java.util.Date.)
                   ;;; XXX need to check sent-email in pc.email to guard against multiple txes!
                   :needs-email :email/permission-granted
                   :permission/granter-ref (:db/id granter)
                   :permission/team-cust (UUID. (:db/id team) (:db/id cust))}])))

(defn convert-access-grant [access-grant cust annotations]
  (let [txid (d/tempid :db.part/tx)
        temp-id (d/tempid :db.part/user)
        doc (:access-grant/document-ref access-grant)
        team (:access-grant/team access-grant)]
    @(d/transact (pcd/conn)
                 [(assoc annotations :db/id txid)
                  (web-peer/retract-entity (:db/id access-grant))
                  (web-peer/server-frontend-id temp-id (or (:db/id doc)
                                                           (:db/id team)))
                  (merge
                   {:db/id temp-id
                    :permission/permits :permission.permits/admin

                    :permission/cust-ref (:db/id cust)
                    :permission/grant-date (or (:access-grant/grant-date access-grant)
                                               (java.util.Date.))}
                   (when doc
                     {:permission/document-ref (:db/id doc)
                      :permission/doc-cust (UUID. (:db/id doc) (:db/id cust))})
                   (when team
                     {:permission/team (:db/id team)
                      :permission/team-cust (UUID. (:db/id team) (:db/id cust))})
                   (when-let [granter (:access-grant/granter-ref access-grant)]
                     {:permission/granter-ref (:db/id granter)}))])))

(defn convert-access-request [access-request granter annotations]
  (let [txid (d/tempid :db.part/tx)
        temp-id (d/tempid :db.part/user)
        doc-id (:db/id (:access-request/document-ref access-request))
        team-id (:db/id (:access-request/team access-request))
        cust (:access-request/cust-ref access-request)]
    @(d/transact (pcd/conn)
                 [(assoc annotations :db/id txid)
                  (web-peer/retract-entity (:db/id access-request))
                  (web-peer/server-frontend-id temp-id (or doc-id team-id))
                  (merge
                   {:db/id temp-id
                    :permission/permits :permission.permits/admin
                    :permission/cust-ref (:db/id cust)
                    :permission/grant-date (java.util.Date.)
                    :permission/granter-ref (:db/id granter)
                    :needs-email :email/permission-granted}
                   (when doc-id
                     {:permission/document-ref doc-id
                      :permission/doc-cust (UUID. doc-id (:db/id cust))})
                   (when team-id
                     {:permission/team team-id
                      :permission/team-cust (UUID. team-id (:db/id cust))}))])))

;; TODO: figure out how to have only 1 read-api (maybe only send datoms?)
(defn read-api [db permission]
  (let [doc-id (:db/id (:permission/document-ref permission))
        team-uuid (:team/uuid (:permission/team permission))
        cust-email (:cust/email (:permission/cust-ref permission))]
    (-> permission
      (select-keys [:permission/permits
                    :permission/grant-date])
      (assoc :db/id (web-peer/client-id permission))
      (cond-> doc-id (assoc :permission/document doc-id)
              team-uuid (assoc :permission/team team-uuid)
              cust-email (assoc :permission/cust cust-email)))))

(defn find-by-document [db doc]
  (->> (d/q '{:find [?t]
              :in [$ ?doc-id]
              :where [[?t :permission/document-ref ?doc-id]]}
            db (:db/id doc))
    (map first)
    (map #(d/entity db %))))

(defn find-by-team [db team]
  (->> (d/q '{:find [?t]
              :in [$ ?team-id]
              :where [[?t :permission/team ?team-id]]}
            db (:db/id team))
    (map first)
    (map #(d/entity db %))))

(defn find-by-token [db token]
  (->> (d/q '{:find [?t]
              :in [$ ?token]
              :where [[?t :permission/token ?token]]}
            db token)
    ffirst
    (d/entity db)))

(defn expired? [permission]
  (when-let [expiry (:permission/expiry permission)]
    (.before expiry (java.util.Date.))))

(defn create-document-image-permission!
  "Creates a token-based permission that can be used to access the svg and png images
   of the document. Used by emails to provide thumbnails."
  [doc]
  (let [temp-id (d/tempid :db.part/user)
        token (crypto.random/url-part 32)
        expiry (-> (time/now) (time/plus (time/weeks 2)) (clj-time.coerce/to-date))
        {:keys [tempids db-after]} @(d/transact (pcd/conn)
                                                [{:db/id temp-id
                                                  :permission/permits #{:permission.permits/read}
                                                  :permission/token token
                                                  :permission/document-ref (:db/id doc)
                                                  :permission/expiry expiry}])]
    (->> (d/resolve-tempid db-after
                           tempids
                           temp-id)
      (d/entity db-after))))
