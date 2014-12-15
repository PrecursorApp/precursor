(ns pc.models.permission
  "cust is what would usually be called user, we call it cust b/c
   Clojure has already taken the name user in the repl"
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
