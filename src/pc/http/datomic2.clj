;; Hack to get around circular dependency
(ns pc.http.datomic2
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [pc.http.sente :as sente]
            [pc.datomic :as pcd]
            [datomic.api :refer [db q] :as d]))

(defn public?
  "Only let the frontend access entities with the entity-ids we create for the frontend"
  [db datom]
  (->> datom :e (d/entity db) :dummy (= :dummy/dummy)))

(defn datom-read-api [db datom]
  (let [{:keys [e a v tx added] :as d} datom
        a (:db/ident (d/entity db a))]
    {:e e :a a :v v :tx tx :added added}))

(defn transact!
  "Takes datoms from tx-data on the frontend and applies them to the backend. Expects datoms to be maps.
   Returns backend's version of the datoms."
  [datoms document-id session-uuid]
  (cond (empty? datoms)
        {:status 400 :body (pr-str {:error "datoms is required and should be non-empty"})}
        (< 100 (count datoms))
        {:status 400 :body (pr-str {:error "You can only transact 100 datoms at once"})}
        (not (number? document-id))
        {:status 400 :body (pr-str {:error "document-id is required and should be an entity id"})}
        :else
        {:status 200
         :body {:datoms (let [db (pcd/default-db)
                              conn (pcd/conn)
                              txid (d/tempid :db.part/tx)]
                          (->> datoms
                               (filter (partial public? db))
                               (map pcd/datom->transaction)
                               (concat [{:db/id txid :document/id document-id :session/uuid session-uuid}])
                               (d/transact conn)
                               deref
                               :tx-data
                               (filter (partial public? db))
                               (map (partial datom-read-api db))))}}))
