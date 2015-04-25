(ns pc.models.team
  (:require [datomic.api :as d]
            [pc.datomic :as pcd]
            [pc.datomic.web-peer :as web-peer]
            [pc.utils :as utils]))

;; We'll pretend we have a type here
#_(t/def-alias Team (HMap :mandatory {:team/subdomain String
                                      :db/id Long
                                      ;; probably a uuid type
                                      :team/uuid String}))

(defn all [db]
  (map (partial d/entity db) (d/q '[:find [?t ...] :where [?t :team/subdomain]] db)))

(defn find-by-subdomain [db subdomain]
  (some->> subdomain
    (d/datoms db :avet :team/subdomain)
    first
    :e
    (d/entity db)))

(defn find-by-uuid [db uuid]
  {:pre [uuid]}
  (some->> (d/datoms db :avet :team/uuid uuid)
    first
    :e
    (d/entity db)))

(defn find-by-plan [db plan]
  (->> (d/datoms db :vaet (:db/id plan) :team/plan)
    first
    :e
    (d/entity db)))

(defn find-by-invoice [db invoice]
  (->> (d/datoms db :vaet (:db/id invoice) :plan/invoices)
    first
    :e
    (d/entity db)
    (find-by-plan db)))

(defn create-for-subdomain! [subdomain cust annotations]
  @(d/transact (pcd/conn) [(merge {:db/id (d/tempid :db.part/tx)}
                                  annotations)
                           (merge
                            {:db/id (d/tempid :db.part/user)
                             :team/subdomain subdomain
                             :team/uuid (d/squuid)}
                            (when (seq cust)
                              {:team/creator (:db/id cust)}))]))

(defn find-doc-ids [db team]
  (map :e (d/datoms db :vaet (:db/id team) :document/team)))

(defn public-read-api [team]
  (select-keys team [:team/subdomain :team/uuid]))

(defn read-api [team]
  (-> team
    (select-keys [:team/subdomain :team/uuid :team/intro-doc :team/plan])
    (utils/update-when-in [:team/intro-doc] :db/id)
    (utils/update-when-in [:team/plan] (fn [p] (web-peer/client-id p)))))
