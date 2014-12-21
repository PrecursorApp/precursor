(ns pc.models.access-grant
  (:require [pc.datomic :as pcd]
            [datomic.api :refer [db q] :as d]))

(defn read-api [grant]
  (select-keys grant [:access-grant/document
                      :access-grant/email
                      :access-grant/expiry
                      :db/id]))

(defn find-by-document [db doc]
  (->> (d/q '{:find [?t]
              :in [$ ?doc-id]
              :where [[?t :access-grant/document ?doc-id]]}
            db (:db/id doc))
    (map first)
    (map #(d/entity db %))))
