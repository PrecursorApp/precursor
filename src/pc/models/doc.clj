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

(defn create-team-doc! [team doc-attrs]
  (create! (merge {:document/name "Untitled"
                   :document/team (:db/id team)
                   :document/privacy :document.privacy/private}
                  doc-attrs)))


(defn find-by-id [db id]
  (let [candidate (d/entity db id)]
    ;; faster than using a datalog query
    (when (:document/name candidate)
      candidate)))

(defn find-by-team-and-id
  "Finds document for a given team, or without a team if team is nil"
  [db team id]
  (let [candidate (find-by-id db id)]
    (when (= (:db/id team) (some-> candidate :document/team :db/id))
      candidate)))


(defn find-by-team-and-invalid-id [db team invalid-id]
  (some->> (d/q '{:find [?t]
                  :in [$ ?invalid-id]
                  :where [[?t :document/invalid-id ?invalid-id]]}
                db invalid-id)
           ffirst
           (find-by-team-and-id db team)))

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
  "Returns document entity ids for every doc touched by the given cust, but not part of a team"
  [db cust]
  (d/q '{:find [[?doc-id ...]]
         :in [$ ?uuid]
         :where [[?t :cust/uuid ?uuid]
                 [?t :transaction/document ?doc-id]
                 (not [?doc-id :document/team])]}
       db (:cust/uuid cust)))

(defn find-touched-by-cust-in-team
  "Returns document entity ids for every doc touched by the given cust in a given team"
  [db cust team]
  (d/q '{:find [[?doc-id ...]]
         :in [$ ?uuid ?team-id]
         :where [[?t :cust/uuid ?uuid]
                 [?t :transaction/document ?doc-id]
                 [?doc-id :document/team ?team-id]]}
       db (:cust/uuid cust) (:db/id team)))

(defn last-updated-time [db doc-id]
  (ffirst (d/q '{:find [(max ?i)]
                 :in [$ ?doc-id]
                 :where [[?t :transaction/document ?doc-id]
                         [?t :transaction/broadcast]
                         [?t :db/txInstant ?i]]}
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
