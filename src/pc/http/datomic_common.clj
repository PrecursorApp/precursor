(ns pc.http.datomic-common
  (:require [clojure.core.memoize :as memo]
            [clojure.tools.logging :as log]
            [pc.datomic :as pcd]
            [datomic.api :refer [db q] :as d]))

(defn public?*
  "Only let the frontend access entities with the entity-ids we create for the frontend"
  [db eid]
  (->> eid (d/entity db) :dummy (= :dummy/dummy)))

(def public? (memo/ttl public?*
                       :ttl/threshold (* 30 1000)))
