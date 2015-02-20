(ns pc.models.doc
  (:require [pc.datomic :as pcd]
            [pc.models.chat-bot :as chat-bot-model]
            [datomic.api :refer [db q] :as d])
  (:import java.util.UUID))

(defn create! [doc-attrs]
  (let [temp-id (d/tempid :db.part/user)
        {:keys [tempids db-after]} @(d/transact (pcd/conn)
                                                [(assoc doc-attrs :db/id temp-id)])]
    (let [eid (d/resolve-tempid db-after
                           tempids
                           temp-id)]
      (-> (d/transact (pcd/conn) [[:db/add eid :frontend/id (UUID. eid eid)]])
        deref
        :db-after
        (d/entity eid)))))

(def default-name "Untitled")

(defn create-public-doc! [doc-attrs]
  (create! (merge {:document/name "Untitled"
                   :document/privacy :document.privacy/public}
                  doc-attrs)))


(defn find-by-id [db id]
  (let [candidate (d/entity db id)]
    ;; faster than using a datalog query
    (when (:document/name candidate)
      candidate)))

(defn find-by-invalid-id [db invalid-id]
  (some->> (d/q '{:find [?t]
                  :in [$ ?invalid-id]
                  :where [[?t :document/invalid-id ?invalid-id]]}
                db invalid-id)
           ffirst
           (find-by-id db)))

(defn find-created-by-cust
  "Returns document entity ids for every doc created by the given cust"
  [db cust]
  (map first
       (d/q '{:find [?t]
              :in [$ ?uuid]
              :where [[?t :document/creator ?uuid]]}
            db (:cust/uuid cust))))

(defn find-touched-by-cust
  "Returns document entity ids for every doc touched by the given cust"
  [db cust]
  (map first
       (d/q '{:find [?doc-id]
              :in [$ ?uuid]
              :where [[?t :cust/uuid ?uuid]
                      [?t :document/id ?doc-id]]}
            db (:cust/uuid cust))))

(defn last-updated-time [db doc-id]
  (ffirst (d/q '{:find [(max ?i)]
                 :in [$ ?doc-id]
                 :where [[_ :document/id ?doc-id ?tx]
                         [?tx :db/txInstant ?i]]}
               db doc-id)))

(defn read-api [doc]
  (-> doc
    (select-keys [:db/id
                  :document/invalid-id
                  :document/privacy
                  :document/creator
                  :document/uuid
                  :document/name
                  :document/chat-bot])
    (update-in [:document/chat-bot] (fn [cb]
                                      (chat-bot-model/read-api
                                       (or cb (rand-nth chat-bot-model/chat-bots)))))))
