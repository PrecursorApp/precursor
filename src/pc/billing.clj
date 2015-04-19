(ns pc.billing
  (:require [clj-time.coerce :refer (to-date)]
            [clj-time.core :as time]
            [datomic.api :as d]
            [pc.datomic :as pcd]
            [pc.models.cust :as cust-model]
            [pc.models.team :as team-model]
            [pc.models.plan :as plan-model]
            [pc.models.permission :as permission-model]
            [pc.util.date :as date-util]))

(def active-threshold 10)

(defn team-member? [db team cust]
  (seq (permission-model/team-permits db team cust)))

(defn team-transaction-counts [db team start-instant end-instant]
  (->> (d/q '{:find [?t ?uuid]
             :in [$ ?team-id ?start-tx ?end-tx]
             :where [[?doc :document/team ?team-id]
                     [?t :transaction/document ?doc]
                     [?t :db/txInstant ?tx]
                     [(<= ?start-tx ?tx)]
                     [(>= ?end-tx ?tx)]
                     [?t :cust/uuid ?uuid]]}
            db (:db/id team) start-instant end-instant)
    (group-by second)
    (reduce-kv (fn [m k v]
                 (let [cust (cust-model/find-by-uuid db k)]
                   ;; TODO: if people start cheating, we'll have to
                   ;;       write a better team-member? fn
                   (if (team-member? db team cust)
                     (assoc m cust (count v))
                     m)))
               {})))

;; TODO: should users be calculated based on activity during the trial?
(defn set-active-users [db team & {:keys [now]
                                   :or {now (time/now)}}]
  (let [plan (:team/plan team)]
    (let [start-time (time/minus now (time/months 1))
          active-custs (->> (team-transaction-counts db team (to-date start-time) (to-date now))
                         (reduce-kv (fn [acc u tx-count]
                                      (if (> tx-count active-threshold)
                                        (conj acc u)
                                        acc))
                                    #{}))]
      @(d/transact (pcd/conn) [{:db/id (d/tempid :db.part/tx)
                                :transaction/team (:db/id team)
                                :transaction/broadcast true}
                               (pcd/replace-many (:db/id plan)
                                                 :plan/active-custs
                                                 (set (map :db/id active-custs)))]))))

(defn set-active-users-cron []
  (let [now (time/now)
        db (pcd/default-db)]
    (doseq [team (team-model/all db)]
      (set-active-users db team :now now))))

(defn init []
  (pc.utils/safe-schedule {:minute [8] :hour [0 8 16]} #'set-active-users-cron))
