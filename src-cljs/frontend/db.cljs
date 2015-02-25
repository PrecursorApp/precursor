(ns frontend.db
  (:require [datascript :as d]
            [frontend.datascript :as ds]
            [frontend.sente :as sente]
            [frontend.utils :as utils :include-macros true]))

(def schema {:layer/child {:db/cardinality :db.cardinality/many}})

(defn make-initial-db [initial-entities]
  (let [conn (d/create-conn schema)]
    (d/transact! conn initial-entities)
    conn))

(defn reset-db! [db-atom initial-entities]
  (reset! db-atom @(make-initial-db initial-entities))
  db-atom)

(defn setup-listener! [db key cast! document-id undo-state sente-state]
  (d/listen!
   db
   key
   (fn [tx-report]
     ;; TODO: figure out why I can send tx-report through controls ch
     ;; (cast! :db-updated {:tx-report tx-report})
     (when (first (filter #(= :server/timestamp (:a %)) (:tx-data tx-report)))
       (cast! :chat-db-updated []))
     (when (-> tx-report :tx-meta :can-undo?)
       (swap! undo-state update-in [:transactions] conj tx-report)
       (when-not (-> tx-report :tx-meta :undo)
         (swap! undo-state assoc-in [:last-undo] nil)))
     (when-not (or (-> tx-report :tx-meta :server-update)
                   (-> tx-report :tx-meta :bot-layer))
       (let [datoms (->> tx-report :tx-data (mapv ds/datom-read-api))]
         (doseq [datom-group (partition-all 1000 datoms)]
           (sente/send-msg sente-state [:frontend/transaction {:datoms datom-group
                                                               :document/id document-id}])))))))

(defn empty-db? [db]
  (empty? (d/datoms db :eavt)))

(defn generate-entity-id
  "Assumes that each entity has a different remainder and that all clients have
   the same multiple.
   Takes a db to guard against duplicate ids, but is not meant to be a protection
   against races. Only guards against a previous client with the same remainder skipping
   ids (probably due to an error).
   Returns a map of :entity-id and new :frontend-id-state"
  [db {:keys [multiple remainder next-id] :as frontend-id-state}]
  (let [eid (loop [res next-id]
              (if (first (d/datoms db :eavt res))
                (recur (+ res multiple))
                res))]
    {:entity-id eid
     :frontend-id-state (assoc frontend-id-state :next-id (+ eid multiple))}))

(defn generate-entity-ids
  "Same as generate-entity-id, but returns a list of :entity-ids"
  [db id-count frontend-id-state]
  (loop [i 0
         entity-ids []
         id-state frontend-id-state]
    (if (>= i id-count)
      {:entity-ids entity-ids
       :frontend-id-state id-state}
      (let [res (generate-entity-id db id-state)]
        (recur (inc i)
               (conj entity-ids (:entity-id res))
               (:frontend-id-state res))))))

(defn get-entity-id
  "Provides an app-state-aware API for generating entity ids, returns a map
   with :entity-id and :state keys. Backwards compatible with dummy ids."
  [app-state]
  (let [{:keys [entity-id frontend-id-state]} (generate-entity-id @(:db app-state) (:frontend-id-state app-state))]
    {:entity-id entity-id :state (assoc app-state :frontend-id-state frontend-id-state)}))

(defn get-entity-ids
  "Provides an app-state-aware API for generating entity ids, returns a map
   with :entity-id and :state keys. Backwards compatible with dummy ids."
  [app-state n]
  (let [{:keys [entity-ids frontend-id-state]} (generate-entity-ids @(:db app-state) n (:frontend-id-state app-state))]
    {:entity-ids entity-ids :state (assoc app-state :frontend-id-state frontend-id-state)}))
