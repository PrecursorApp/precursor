(ns pc.http.datomic
  (:require [pc.datomic :as pcd]
            [datomic.api :refer [db q] :as d]))


(defn entity-id-request [eid-count]
  (cond (not (number? eid-count))
        {:status 400 :body (pr-str {:error "count is required and should be a number"})}
        (< 100 eid-count)
        {:status 400 :body (pr-str {:error "You can only ask for 100 entity ids"})}
        :else
        {:status 200 :body (pr-str {:entity-ids (pcd/generate-eids (pcd/conn) eid-count)})}))

(defn public?
  "Only let the frontend access entities with the entity-ids we create for the frontend"
  [db datom]
  (->> datom :e (d/entity db) :dummy (= :dummy/dummy)))

(defn datom-read-api [datom]
  (let [{:keys [e a v tx added] :as d} datom]
    {:e e :a a :v v :tx tx :added added}))

;; TODO: annotate transaction with session and document information
;; TODO: can we expect datoms to be maps?
(defn transact!
  "Takes datoms from tx-data on the frontend and applies them to the backend. Expects datoms to be maps.
   Returns backend's version of the datoms."
  [datoms]
  (cond (empty? datoms)
        {:status 400 :body (pr-str {:error "datoms is required and should be non-empty"})}
        (< 100 (count datoms))
        {:status 400 :body (pr-str {:error "You can only transact 100 datoms at once"})}
        :else
        {:status 200
         :body {:datoms (let [db (pcd/default-db)
                              conn (pcd/conn)]
                          (->> datoms
                               (filter (partial public? db))
                               (map pcd/datom->transaction)
                               (d/transact conn)
                               deref
                               :tx-data
                               (filter (partial public? db))
                               (map datom-read-api)))}}))
