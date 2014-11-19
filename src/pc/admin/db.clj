(ns pc.admin.db
  (:require [datomic.api :refer [db q] :as d]
            [clj-time.core :as time]
            [clj-time.coerce :refer [to-date]]
            [pc.auth]
            [pc.datomic :as pcd]
            [slingshot.slingshot :refer (try+)]))

(defn interesting-doc-ids [{:keys [start-time end-time layer-threshold limit]
                            :or {start-time (time/minus (time/now) (time/days 1))
                                 end-time (time/now)
                                 layer-threshold 10
                                 limit 100}}]
  (let [db (pcd/default-db)
        index-range (d/index-range db :db/txInstant (to-date start-time) (to-date end-time))
        earliest-tx (some-> index-range first .tx)
        latest-tx (some-> index-range last .tx)
        doc-ids (when (and earliest-tx latest-tx)
                  (map first (d/q '{:find [?d] :in [$ ?earliest-tx ?latest-tx]
                                    :where [[?t :document/id ?d ?tx]
                                            [?t :layer/name]
                                            [(<= ?earliest-tx ?tx)]
                                            [(>= ?latest-tx ?tx)]]}
                                  db earliest-tx latest-tx)))]
    (take limit (filter (fn [doc-id]
                          (< layer-threshold (or (ffirst (d/q '{:find [(count ?t)] :in [$ ?d]
                                                                :where [[?t :document/id ?d]
                                                                        [?t :layer/name]]}
                                                              db doc-id))
                                                 0)))
                        doc-ids))))

(defn populate-user-info-from-sub []
  (let [db (pcd/default-db)]
    (doseq [[eid] (d/q '{:find [?t]
                         :where [[?t :google-account/sub]]}
                       db)
            :let [cust (d/entity db eid)]]
      (try+
        (println "adding" (:cust/email cust))
        (pc.auth/update-user-from-sub cust)
        (catch :status t
          (println "error updating" (d/touch cust)))))))
