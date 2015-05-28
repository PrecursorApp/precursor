(ns pc.models.clip
  "cust is what would usually be called user, we call it cust b/c
   Clojure has already taken the name user in the repl"
  (:require [pc.datomic :as pcd]
            [datomic.api :refer [db q] :as d]))

(defn find-by-cust [db cust]
  (map #(d/entity db (:v %))
       (d/datoms db :eavt (:db/id cust) :cust/clips)))

;; TODO: make sure this lookup is fast
(defn find-by-cust-and-uuid [db cust uuid]
  (d/entity db (d/q '{:find [?e .]
                      :in [$ ?cust-id ?uuid]
                      :where [[?e :clip/uuid ?uuid]
                              [?cust-id :cust/clips ?e]]}
                    db (:db/id cust) uuid)))
