(ns pc.models.permission
  (:require [pc.datomic :as pcd]
            [clj-time.coerce]
            [clj-time.core :as time]
            [crypto.random]
            [datomic.api :refer [db q] :as d]))

(defn permits [db doc cust]
  (set (map first (d/q '{:find [?permits]
                         :in [$ ?db-id ?cust-id]
                         :where [[?t :permission/document ?db-id]
                                 [?t :permission/cust ?cust-id]
                                 [?t :permission/permits ?permit-id]
                                 [?permit-id _ ?permits]]}
                       db (:db/id doc) (:db/id cust)))))

(defn grant-permit [doc cust permit annotations]
  (let [txid (d/tempid :db.part/tx)]
    @(d/transact (pcd/conn)
                 [(assoc annotations :db/id txid)
                  [:pc.models.permission/grant-permit (:db/id doc) (:db/id cust) permit (java.util.Date.)]])))

(defn convert-access-grant [access-grant cust annotations]
  (let [txid (d/tempid :db.part/tx)]
    @(d/transact (pcd/conn)
                 [(assoc annotations :db/id txid)
                  [:pc.models.permission/grant-permit
                   (:access-grant/document access-grant)
                   (:db/id cust)
                   :permission.permits/admin
                   (or (:access-grant/grant-date access-grant)
                       (java.util.Date.))]
                  [:db.fn/retractEntity (:db/id access-grant)]])))

(defn convert-access-request [access-request annotations]
  (let [txid (d/tempid :db.part/tx)]
    @(d/transact (pcd/conn)
                 [(assoc annotations :db/id txid)
                  [:pc.models.permission/grant-permit
                   (:access-request/document access-request)
                   (:access-request/cust access-request)
                   :permission.permits/admin
                   (java.util.Date.)]
                  [:db.fn/retractEntity (:db/id access-request)]])))

;; TODO: figure out how to have only 1 read-api (maybe only send datoms?)
(defn read-api [db permission]
  (-> permission
    (select-keys [:permission/document
                  :permission/cust
                  :permission/permits
                  :permission/grant-date
                  :db/id])
    (#(into {} %))
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
    first
    (d/entity db)))

(defn expired? [permission]
  (when-let [expiry (:permission/expiry permission)]
    (.after expiry (java.util.Date.))))

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
