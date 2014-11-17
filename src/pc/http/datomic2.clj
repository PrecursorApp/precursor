;; Hack to get around circular dependency
(ns pc.http.datomic2
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [pc.datomic :as pcd]
            [pc.http.datomic-common :as common]
            [datomic.api :refer [db q] :as d])
  (:import java.util.UUID))


(defn get-float-attrs [db]
  (set (map last (d/q '{:find [?t ?i]
                        :where [[?t :db/valueType :db.type/float]
                                [?t :db/ident ?i]]}
                      db))))

(defn get-uuid-attrs [db]
  (set (map last (d/q '{:find [?t ?i]
                        :where [[?t :db/valueType :db.type/uuid]
                                [?t :db/ident ?i]]}
                      db))))

(defn coerce-floats [float-attrs [type e a v :as transaction]]
  (if (contains? float-attrs a)
    [type e a (float v)]
    transaction))

(defn coerce-uuids [uuid-attrs [type e a v :as transaction]]
  (if (and (contains? uuid-attrs a) (string? v))
    [type e a (UUID/fromString v)]
    transaction))

(defn coerce-server-timestamp [server-timestamp [type e a v :as transaction]]
  (if (and (= :db/add type) (= :server/timestamp a))
    [type e a server-timestamp]
    transaction))

;; TODO: teach the frontend how to lookup cust/name from cust/uuid
(defn chat-cust-name? [[type e a v :as transaction]]
  (= a :chat/cust-name))

(defn transact!
  "Takes datoms from tx-data on the frontend and applies them to the backend. Expects datoms to be maps.
   Returns backend's version of the datoms."
  [datoms document-id session-uuid cust-uuid]
  (cond (empty? datoms)
        {:status 400 :body (pr-str {:error "datoms is required and should be non-empty"})}
        (< 1000 (count datoms))
        {:status 400 :body (pr-str {:error "You can only transact 1000 datoms at once"})}
        (not (number? document-id))
        {:status 400 :body (pr-str {:error "document-id is required and should be an entity id"})}
        :else
        {:status 200
         :body {:datoms (let [db (pcd/default-db)
                              conn (pcd/conn)
                              txid (d/tempid :db.part/tx)
                              float-attrs (get-float-attrs db)
                              uuid-attrs (get-uuid-attrs db)
                              server-timestamp (java.util.Date.)]
                          (->> datoms
                               (filter (partial common/public? db))
                               (map pcd/datom->transaction)
                               (map (partial coerce-floats float-attrs))
                               (map (partial coerce-uuids uuid-attrs))
                               (map (partial coerce-server-timestamp server-timestamp))
                               (remove chat-cust-name?)
                               (concat [(merge {:db/id txid :document/id document-id :session/uuid session-uuid}
                                               (when cust-uuid {:cust/uuid cust-uuid}))])
                               (d/transact conn)
                               deref
                               :tx-data
                               (filter (partial common/public? db))
                               (map (partial common/datom-read-api db))))}}))
