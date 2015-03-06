(ns pc.models.access-request
  (:require [datomic.api :refer [db q] :as d]
            [pc.datomic :as pcd]
            [pc.datomic.web-peer :as web-peer]
            [pc.utils :as utils])
  (:import java.util.UUID))

;; TODO: figure out how to have only 1 read-api (maybe only send datoms?)
(defn read-api [db permission]
  (-> permission
    (select-keys [:access-request/document
                  :access-request/cust
                  :access-request/create-date
                  :access-request/deny-date
                  ;; TODO: different read api based on permissions
                  :access-request/status])
    (assoc :db/id (web-peer/client-id permission))
    (utils/update-when-in [:access-request/cust] #(:cust/email (d/entity db %)))))

(defn requester-read-api [db permission]
  (-> (read-api db permission)
    (select-keys [:access-request/document
                  :access-request/cust
                  :access-request/create-date
                  :db/id])))

(defn find-by-id [db id]
  (let [candidate (d/entity db id)]
    ;; faster than using a datalog query
    (when (:access-request/document candidate)
      candidate)))

(defn find-by-client-part [db namespace-part client-part]
  (let [candidate (d/entity db (web-peer/find-entity-id db namespace-part client-part))]
    ;; faster than using a datalog query
    (when (:access-request/document candidate)
      candidate)))

(defn find-by-document [db doc]
  (->> (d/q '{:find [?t]
              :in [$ ?doc-id]
              :where [[?t :access-request/document ?doc-id]]}
            db (:db/id doc))
    (map first)
    (map #(d/entity db %))))

(defn find-by-doc-and-cust [db doc cust]
  (->> (d/q '{:find [?t]
              :in [$ ?doc-id ?cust-id]
              :where [[?t :access-request/document ?doc-id]
                      [?t :access-request/cust ?cust-id]]}
            db (:db/id doc) (:db/id cust))
    (map first)
    (map #(d/entity db %))))

(defn create-request [doc cust annotations]
  (let [txid (d/tempid :db.part/tx)
        create-date (java.util.Date.)
        temp-id (d/tempid :db.part/user)]
    @(d/transact (pcd/conn)
                 [(assoc annotations :db/id txid)
                  {:db/id temp-id
                   :access-request/document (:db/id doc)
                   :access-request/document-ref (:db/id doc)
                   :access-request/cust (:db/id cust)
                   :access-request/cust-ref (:db/id cust)
                   :access-request/status :access-request.status/pending
                   :access-request/create-date create-date
                   :needs-email :email/access-request-created
                   :access-request/doc-cust (UUID. (:db/id doc) (:db/id cust))}
                  (web-peer/server-frontend-id temp-id (:db/id doc))])))

(defn deny-request [request annotations]
  (let [txid (d/tempid :db.part/tx)
        deny-date (java.util.Date.)]
    @(d/transact (pcd/conn)
                 [(assoc annotations :db/id txid)
                  ;; TODO: need a way to let the frontend perform history queries
                  {:db/id (:db/id request)
                   :access-request/status :access-request.status/denied
                   :access-request/deny-date deny-date}])))
