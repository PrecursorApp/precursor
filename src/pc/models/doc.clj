(ns pc.models.doc
  (:require [pc.datomic :as pcd]
            [datomic.api :refer [db q] :as d]))

(defn create! [doc-attrs]
  (let [temp-id (d/tempid :db.part/user)
        {:keys [tempids db-after]} @(d/transact (pcd/conn)
                                                [(assoc doc-attrs :db/id temp-id)])]
    (->> (d/resolve-tempid db-after
                           tempids
                           temp-id)
         (d/entity db-after)
         pcd/touch+)))

(defn find-created-by-cust
  "Returns document entity ids for every doc created by the given cust"
  [db cust]
  (map first
       (d/q '{:find [?t]
              :in [$ ?uuid]
              :where [[?t :document/creator ?uuid]]}
            db (:cust/uuid cust))))
