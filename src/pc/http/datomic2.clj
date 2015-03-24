(ns pc.http.datomic2
  (:require [clojure.core.async :as async]
            [clojure.set :as set]
            [clojure.tools.logging :as log]
            [datomic.api :refer [db q] :as d]
            [pc.datomic :as pcd]
            [pc.datomic.web-peer :as web-peer])
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

(defn coerce-session-uuid [session-uuid [type e a v :as transaction]]
  (if (= :session/uuid a)
    [type e a session-uuid]
    transaction))

(defn coerce-cust-uuid [cust-uuid [type e a v :as transaction]]
  (if (= :cust/uuid a)
    [type e a cust-uuid]
    transaction))

(def read-scope-whitelist
  #{:session/uuid
    :chat/document
    :chat/body
    :chat/color
    :cust/uuid
    :client/timestamp
    :server/timestamp})

(defn incoming-whitelist [scope]
  (case scope
    :read read-scope-whitelist
    :admin (set/union read-scope-whitelist
                      #{:layer/name
                        :layer/uuid
                        :layer/type
                        :layer/start-x
                        :layer/start-y
                        :layer/end-x
                        :layer/end-y
                        :layer/rx
                        :layer/ry
                        :layer/stroke-width
                        :layer/stroke-color
                        :layer/opacity
                        :layer/start-sx
                        :layer/start-sy
                        :layer/fill
                        :layer/font-family
                        :layer/text
                        :layer/font-size
                        :layer/path
                        :layer/child
                        :layer/ui-id
                        :layer/ui-target
                        :layer/points-to
                        :layer/document
                        :document/name
                        :entity/type})))

(defn whitelisted? [scope [type e a v :as transaction]]
  (contains? (incoming-whitelist scope) a))

(defn can-modify?
  "If the user has read scope, makes sure that they don't modify existing txes"
  [db document-id scope {:keys [remainder multiple]} [type e a v :as transaction]]
  (cond (= scope :admin) true
        (= scope :read) (and (= remainder (mod e multiple))
                             (not (pc.utils/inspect (web-peer/taken-id? db document-id e))))))

(defn remove-float-conflicts [txes]
  (vals (reduce (fn [tx-index [type e a v :as tx]]
                  (let [index-key [e a v]]
                    (if (and (float? v) (contains? tx-index index-key))
                      (dissoc tx-index index-key)
                      (assoc tx-index index-key tx))))
                {} txes)))

(defn add-frontend-ids [document-id txes]
  (let [eid-map (zipmap (set (map second txes)) (repeatedly #(d/tempid :db.part/user)))
        frontend-id-txes (map (fn [[e tempid]] [:db/add tempid :frontend/id (UUID. document-id e)]) eid-map)]
    (concat (map (fn [tx]
                   (-> tx
                     (update-in [1] eid-map)
                     (#(if (= :layer/points-to (nth tx 2))
                         (update-in % [3] (fn [e]
                                            (or (get eid-map e)
                                                [:frontend/id (UUID. document-id e)])))
                         %))))
                 txes)
            frontend-id-txes)))

;; TODO: only let creators mark things as private
;; TODO: only let people on the white list make things as private

(defn transact!
  "Takes datoms from tx-data on the frontend and applies them to the backend. Expects datoms to be maps.
   Returns backend's version of the datoms."
  [datoms {:keys [document-id team-id client-id session-uuid cust-uuid access-scope frontend-id-seed receive-instant]}]
  (cond (empty? datoms)
        {:status 400 :body (pr-str {:error "datoms is required and should be non-empty"})}
        (< 1500 (count datoms))
        {:status 400 :body (pr-str {:error "You can only transact 1500 datoms at once"})}
        (not (number? document-id))
        {:status 400 :body (pr-str {:error "document-id is required and should be an entity id"})}
        :else
        {:status 200
         :body {:datoms (let [db (pcd/default-db)
                              conn (pcd/conn)
                              txid (d/tempid :db.part/tx)
                              float-attrs (get-float-attrs db)
                              uuid-attrs (get-uuid-attrs db)
                              server-timestamp (or receive-instant (java.util.Date.))]
                          (->> datoms
                            (map pcd/datom->transaction)
                            (map (partial coerce-floats float-attrs))
                            (map (partial coerce-uuids uuid-attrs))
                            (map (partial coerce-server-timestamp server-timestamp))
                            (map (partial coerce-session-uuid session-uuid))
                            (map (partial coerce-cust-uuid cust-uuid))
                            (filter (partial whitelisted? access-scope))
                            (filter (partial can-modify? db document-id access-scope frontend-id-seed))
                            (remove-float-conflicts)
                            (add-frontend-ids (or document-id team-id))
                            (concat [(merge {:db/id txid
                                             :session/uuid session-uuid
                                             :session/client-id client-id
                                             :transaction/broadcast true}
                                            (when document-id {:transaction/document document-id})
                                            (when team-id {:transaction/team team-id})
                                            (when cust-uuid {:cust/uuid cust-uuid}))])
                            (d/transact conn)
                            deref))}}))
