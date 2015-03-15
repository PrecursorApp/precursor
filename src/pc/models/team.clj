(ns pc.models.team
  (:require [datomic.api :as d]
            [pc.datomic :as pcd]
            [pc.utils :as utils]))

;; We'll pretend we have a type here
#_(t/def-alias Team (HMap :mandatory {:team/subdomain String
                                      :db/id Long
                                      ;; probably a uuid type
                                      :team/uuid String}))

(defn find-by-subdomain [db subdomain]
  (some->> subdomain
    (d/datoms db :avet :team/subdomain)
    first
    :e
    (d/entity db)))

(defn find-by-uuid [db uuid]
  {:pre [uuid]}
  (some->> (d/datoms db :avet :team/uuid uuid)
    first
    :e
    (d/entity db)))

(defn create-for-subdomain! [subdomain annotations]
  @(d/transact (pcd/conn) [(merge {:db/id (d/tempid :db.part/tx)}
                                  annotations)
                           {:db/id (d/tempid :db.part/user)
                            :team/subdomain subdomain
                            :team/uuid (d/squuid)}]))

(defn find-doc-ids [db team]
  (map :e (d/datoms db :vaet (:db/id team) :document/team)))

(defn public-read-api [team]
  (select-keys team [:team/subdomain :team/uuid]))

(defn read-api [team]
  (-> team
    (select-keys [:team/subdomain :team/uuid :team/intro-doc])
    (utils/update-when-in [:team/intro-doc] :db/id)))
