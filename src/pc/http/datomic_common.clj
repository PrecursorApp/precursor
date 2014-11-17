(ns pc.http.datomic-common
  (:require [clojure.tools.logging :as log]
            [pc.datomic :as pcd]
            [datomic.api :refer [db q] :as d]))

(defn public?
  "Only let the frontend access entities with the entity-ids we create for the frontend"
  [db datom]
  (->> datom :e (d/entity db) :dummy (= :dummy/dummy)))

(defn enum? [a]
  (contains? #{:layer/type :entity/type} a))

(defn datom-read-api [db datom]
  (let [{:keys [e a v tx added] :as d} datom
        a (:db/ident (d/entity db a))
        v (if (enum? a)
            (:db/ident (d/entity db v))
            v)]
    {:e e :a a :v v :tx tx :added added}))
