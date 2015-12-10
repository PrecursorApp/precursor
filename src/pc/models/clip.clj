(ns pc.models.clip
  "cust is what would usually be called user, we call it cust b/c
   Clojure has already taken the name user in the repl"
  (:require [pc.http.clipboard :as clipboard]
            [pc.datomic :as pcd]
            [pc.profile :as profile]
            [datomic.api :refer [db q] :as d]))

(defn find-by-cust [db cust]
  (map #(d/entity db (:v %))
       (d/datoms db :eavt (:db/id cust) :cust/clips)))

(defn find-important-by-cust [db cust]
  (map (partial d/entity db)
       (d/q '{:find [[?e ...]]
              :in [$ ?cust-id]
              :where [[?cust-id :cust/clips ?e]
                      [?e :clip/important? true]]}
            db (:db/id cust))))

(defn find-by-cust-and-uuid [db cust uuid]
  (d/entity db (d/q '{:find [?e .]
                      :in [$ ?cust-id ?uuid]
                      :where [[?e :clip/uuid ?uuid]
                              [?cust-id :cust/clips ?e]]}
                    db (:db/id cust) uuid)))

(defn generate-default-clips []
  #{{:db/id (d/tempid :db.part/user)
     :clip/s3-bucket (profile/clipboard-bucket)
     :clip/s3-key "iphone"
     :clip/uuid (d/squuid)
     :clip/important? true}})

(defn read-api [clip]
  (-> clip
    (select-keys [:clip/uuid :clip/important?])
    (assoc :clip/s3-url (clipboard/create-presigned-clip-url clip))))
