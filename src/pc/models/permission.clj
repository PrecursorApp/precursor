(ns pc.models.permission
  (:require [pc.datomic :as pcd]
            [datomic.api :refer [db q] :as d]))

(defn permits [db doc cust]
  (set (map first (d/q '{:find [?permits]
                         :in [$ ?db-id ?cust-id]
                         :where [[?t :permission/document ?db-id]
                                 [?t :permission/cust ?cust-id]
                                 [?t :permission/permits ?permit-id]
                                 [?permit-id _ ?permits]]}
                       db (:db/id doc) (:db/id cust)))))

(defn grant-permit [doc cust permit]
  @(d/transact (pcd/conn) [[:pc.models.permission/grant-permit (:db/id doc) (:db/id cust) permit]]))

(defn read-api [db permission]
  (-> permission
    (select-keys [:permission/document
                  :permission/cust
                  :permission/permits
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
