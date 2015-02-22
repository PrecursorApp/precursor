(ns pc.models.permission
  (:require [pc.datomic :as pcd]
            [pc.datomic.web-peer :as web-peer]
            [clj-time.coerce]
            [clj-time.core :as time]
            [crypto.random]
            [datomic.api :refer [db q] :as d])
  (:import java.util.UUID))

(defn permits [db doc cust]
  (set (map first (d/q '{:find [?permits]
                         :in [$ ?db-id ?cust-id]
                         :where [[?t :permission/document ?db-id]
                                 [?t :permission/cust ?cust-id]
                                 [?t :permission/permits ?permit-id]
                                 [?permit-id _ ?permits]]}
                       db (:db/id doc) (:db/id cust)))))

(defn grant-permit [doc granter cust permit annotations]
  (let [txid (d/tempid :db.part/tx)
        temp-id (d/tempid :db.part/user)]
    @(d/transact (pcd/conn)
                 [(assoc annotations :db/id txid)
                  (web-peer/server-frontend-id temp-id (:db/id doc))
                  {:db/id temp-id
                   :permission/permits permit
                   :permission/document (:db/id doc)
                   :permission/cust (:db/id cust)
                   :permission/grant-date (java.util.Date.)
                   ;;; XXX need to check sent-email in pc.email to guard against multiple txes!
                   :needs-email :email/document-permission-for-customer-granted
                   :permission/granter (:db/id granter)
                   :permission/doc-cust (UUID. (:db/id doc) (:db/id cust))}])))

(defn convert-access-grant [access-grant cust annotations]
  (let [txid (d/tempid :db.part/tx)
        temp-id (d/tempid :db.part/user)
        doc-id (:access-grant/document access-grant)]
    @(d/transact (pcd/conn)
                 [(assoc annotations :db/id txid)
                  (web-peer/retract-entity (:db/id access-grant))
                  (web-peer/server-frontend-id temp-id doc-id)
                  (merge
                   {:db/id temp-id
                    :permission/permits :permission.permits/admin
                    :permission/document doc-id
                    :permission/cust (:db/id cust)
                    :permission/grant-date (or (:access-grant/grant-date access-grant)
                                               (java.util.Date.))
                   ;;; XXX need to check sent-email in pc.email to guard against multiple txes!
                    :permission/doc-cust (UUID. doc-id (:db/id cust))}
                   (when-let [granter (:access-grant/granter access-grant)]
                     {:permission/granter granter}))])))

(defn convert-access-request [access-request granter annotations]
  (let [txid (d/tempid :db.part/tx)
        temp-id (d/tempid :db.part/user)
        doc-id (:access-request/document access-request)
        cust-id (:access-request/cust access-request)]
    @(d/transact (pcd/conn)
                 [(assoc annotations :db/id txid)
                  (web-peer/retract-entity (:db/id access-request))
                  (web-peer/server-frontend-id temp-id doc-id)
                  {:db/id temp-id
                   :permission/permits :permission.permits/admin
                   :permission/document doc-id
                   :permission/cust cust-id
                   :permission/grant-date (java.util.Date.)
                   :permission/granter (:db/id granter)
                   :permission/doc-cust (UUID. doc-id cust-id)}])))

;; TODO: figure out how to have only 1 read-api (maybe only send datoms?)
(defn read-api [db permission]
  (-> permission
    (select-keys [:permission/document
                  :permission/cust
                  :permission/permits
                  :permission/grant-date])
    (assoc :db/id (web-peer/client-id permission))
    (update-in [:permission/cust] #(:cust/email (d/entity db %)))))

(defn find-by-document [db doc]
  (->> (d/q '{:find [?t]
              :in [$ ?doc-id]
              :where [[?t :permission/document ?doc-id]]}
            db (:db/id doc))
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
                                                  ;; TODO: ref for doc
                                                  :permission/document (:db/id doc)
                                                  :permission/expiry expiry}])]
    (->> (d/resolve-tempid db-after
                           tempids
                           temp-id)
      (d/entity db-after))))

(defn get-granter [db permission]
  (d/entity db (:permission/granter permission)))
