(ns pc.admin.db
  (:require [datomic.api :refer [db q] :as d]
            [clojure.tools.logging :as log]
            [clj-time.core :as time]
            [clj-time.coerce :refer [to-date]]
            [org.httpkit.server :as httpkit]
            [pc.datomic :as pcd]
            [pc.models.doc :as doc-model]
            [pc.models.layer :as layer-model]))

(defn interesting-doc-ids [{:keys [start-time end-time layer-threshold limit]
                            :or {start-time (time/minus (time/now) (time/days 1))
                                 end-time (time/now)
                                 layer-threshold 10
                                 limit 100}}]
  (let [db (pcd/default-db)
        index-range (d/index-range db :db/txInstant (to-date start-time) (to-date end-time))
        earliest-tx (some-> index-range first .tx)
        latest-tx (some-> index-range last .tx)
        doc-ids (when (and earliest-tx latest-tx)
                  (map first (d/q '{:find [?d] :in [$ ?earliest-tx ?latest-tx]
                                    :where [[?t :document/id ?d ?tx]
                                            [?t :layer/name]
                                            [(<= ?earliest-tx ?tx)]
                                            [(>= ?latest-tx ?tx)]]}
                                  db earliest-tx latest-tx)))]
    (take limit (filter (fn [doc-id]
                          (< layer-threshold (or (ffirst (d/q '{:find [(count ?t)] :in [$ ?d]
                                                                :where [[?t :document/id ?d]
                                                                        [?t :layer/name]]}
                                                              db doc-id))
                                                 0)))
                        doc-ids))))

(defn copy-document
  "Creates a copy of the document, without any of the document's history"
  [db doc]
  (let [layers (layer-model/find-by-document db doc)
        new-doc (doc-model/create-public-doc! {:document/name (str "Clone of " (:document/name doc))})]
    @(d/transact (pcd/conn) (map (fn [l] (assoc (pcd/touch+ l)
                                                :db/id (d/tempid :db.part/user)
                                                :document/id (:db/id new-doc)
                                                :layer/document new-doc))
                                 layers))
    new-doc))
