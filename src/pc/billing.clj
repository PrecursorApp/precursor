(ns pc.billing
  (:require [datomic.api :as d]
            [pc.datomic :as pcd]
            [pc.models.cust :as cust-model]
            [pc.models.permission :as permission-model]))

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
