(ns frontend.db
  (:require [datascript :as d]
            [frontend.datascript :as ds]
            [frontend.sente :as sente]))

(def schema {:layer/child {:db/cardinality :db.cardinality/many}})

(defn make-initial-db []
  (d/create-conn schema))

(defn reset-db! [db-atom]
  (reset! db-atom @(make-initial-db))
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
     (when-not (-> tx-report :tx-meta :server-update)
       (let [datoms (->> tx-report :tx-data (mapv ds/datom-read-api))]
         (doseq [datom-group (partition-all 500 datoms)]
           (sente/send-msg sente-state [:frontend/transaction {:datoms datom-group
                                                               :document/id document-id}])))))))
