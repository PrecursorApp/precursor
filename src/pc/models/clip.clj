(ns pc.models.clip
  "cust is what would usually be called user, we call it cust b/c
   Clojure has already taken the name user in the repl"
  (:require [pc.datomic :as pcd]
            [datomic.api :refer [db q] :as d]))

(defn find-by-cust [db cust]
  (map #(d/entity db (:v %))
       (d/datoms db :eavt (:db/id cust) :cust/clips)))
