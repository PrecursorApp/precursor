(ns pc.models.access-request
  (:require [pc.datomic :as pcd]
            [datomic.api :refer [db q] :as d]))

;; TODO: figure out how to have only 1 read-api (maybe only send datoms?)
(defn read-api [db permission]
  (-> permission
    (select-keys [:access-request/document
                  :access-request/cust
                  :db/id])
    (#(into {} %))
    (update-in [:access-request/cust] #(:cust/email (d/entity db %)))))


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
  (let [txid (d/tempid :db.part/tx)]
    @(d/transact (pcd/conn)
                 [(assoc annotations :db/id txid)
                  [:pc.models.access-request/create-request (:db/id doc) (:db/id cust) [:needs-email :email/access-request-created]]])))
