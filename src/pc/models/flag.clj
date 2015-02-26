(ns pc.models.flag
  "cust is what would usually be called user, we call it cust b/c
   Clojure has already taken the name user in the repl"
  (:require [pc.datomic :as pcd]
            [datomic.api :refer [db q] :as d]))

(defn add-flag-tx [ent flag]
  [:db/add (:db/id ent) :flags flag])

(defn add-flag [ent flag]
  @(d/transact (pcd/conn) [(add-flag-tx ent flag)]))

(defn remove-flag [ent flag]
  @(d/transact (pcd/conn) [[:db/retract (:db/id ent) :flags flag]]))
