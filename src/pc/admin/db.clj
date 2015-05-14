(ns pc.admin.db
  (:require [datomic.api :refer [db q] :as d]
            [clojure.tools.logging :as log]
            [clj-time.core :as time]
            [clj-time.coerce :refer [to-date]]
            [org.httpkit.server :as httpkit]
            [pc.analytics :as analytics]
            [pc.auth.google :as google-auth]
            [pc.datomic :as pcd]
            [pc.mixpanel :as mixpanel]
            [pc.models.cust :as cust-model]
            [pc.models.doc :as doc-model]
            [pc.models.layer :as layer-model]
            [pc.utils :as utils]))

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
                                    :where [[?t :layer/document ?d ?tx]
                                            [(<= ?earliest-tx ?tx)]
                                            [(>= ?latest-tx ?tx)]]}
                                  db earliest-tx latest-tx)))]
    (take limit (filter (fn [doc-id]
                          (< layer-threshold (or (ffirst (d/q '{:find [(count ?t)] :in [$ ?d]
                                                                :where [[?t :layer/document ?d]]}
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
                                                :layer/document new-doc))
                                 layers))
    new-doc))

(defn transfer-doc-to-team [doc team]
  @(d/transact (pcd/conn) [[:db/add (:db/id doc) :document/team (:db/id team)]]))
