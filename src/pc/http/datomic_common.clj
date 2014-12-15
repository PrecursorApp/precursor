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

(defn enum? [a]
  (contains? #{:layer/type :entity/type} a))

(defn find-chat-name [db cust-uuid]
  (ffirst (d/q '{:find [?name] :in [$ ?uuid]
                 :where [[?t :cust/uuid ?uuid]
                         [?t :cust/name ?name]]}
               db cust-uuid)))

;; TODO: teach the frontend how to lookup name from cust/uuid
;;       this will break if something else is associating cust/uuids
(defn maybe-replace-cust-uuid [db {:keys [a] :as d}]
  (if (= a :cust/uuid)
    (assoc d
      :a :chat/cust-name
      :v (find-chat-name db (:v d)))
    d))

(defn datom-read-api [db datom]
  (let [{:keys [e a v tx added] :as d} datom
        a (:db/ident (d/entity db a))
        v (if (enum? a)
            (:db/ident (d/entity db v))
            v)]
    (->> {:e e :a a :v v :tx tx :added added}
         (maybe-replace-cust-uuid db))))
