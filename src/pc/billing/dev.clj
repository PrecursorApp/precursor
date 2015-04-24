(ns pc.billing.dev
  (:require [clj-time.core :as time]
            [datomic.api :as d]
            [pc.billing :as billing]
            [pc.datomic :as pcd]
            [pc.models.cust :as cust-model]
            [pc.models.permission :as permission-model]))

(defn ensure-cust [db email]
  (or (cust-model/find-by-email db email)
      (cust-model/create! {:cust/email email
                           :cust/uuid (d/squuid)})))

(defn add-billing-cust-to-team [team email]
  (let [db (pcd/default-db)
        cust (ensure-cust db email)
        prcrsr-bot (cust-model/prcrsr-bot db)]
    (permission-model/grant-team-permit team
                                        prcrsr-bot
                                        cust
                                        :permission.permits/admin
                                        {:cust/uuid (:cust/uuid prcrsr-bot)
                                         :transaction/team (:db/id team)
                                         :transaction/broadcast true})
    (dotimes [x (inc billing/active-threshold)]
      @(d/transact (pcd/conn) [{:db/id (d/tempid :db.part/tx)
                                :transaction/document (:db/id (:team/intro-doc team))
                                :transaction/broadcast true
                                :cust/uuid (:cust/uuid cust)}]))
    (billing/set-active-users (pcd/default-db) team)))

(defn remove-billing-cust-from-team [team email]
  (let [db (pcd/default-db)
        cust (ensure-cust db email)
        prcrsr-bot (cust-model/prcrsr-bot db)]
    (permission-model/revoke-team-permissions db
                                              team
                                              prcrsr-bot
                                              cust
                                              {:cust/uuid (:cust/uuid prcrsr-bot)
                                               :transaction/team (:db/id team)
                                               :transaction/broadcast true}))
  (billing/set-active-users (pcd/default-db) team))
