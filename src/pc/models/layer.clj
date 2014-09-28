(ns pc.models.layer
  (:require [pc.datomic :as pcd]
            [datomic.api :refer [db q] :as d]))


;; We'll pretend we have a type here
#_(t/def-alias Layer (HMap :mandatory {:document/id Long
                                       :db/id Long
                                       :layer/name String}
                           :optional {:layer/type Keyword
                                      :layer/child Long}))

;; This will work as long as other things don't get a document id
(defn all [db]
  (pcd/touch-all '{:find [?t]
                   :where [[?t :layer/name]]}
                 db))

(defn find-by-document [db document]
  (pcd/touch-all '{:find [?t] :in [$ ?document-id]
                   :where [[?t :document/id ?document-id]]}
                 db (:db/id document)))


(comment
  (d/transact (pcd/conn)
              [{:db/id (d/tempid :db.part/user)
                :layer/name "Test Layer 1"
                :layer/fill "red"}
               {:db/id (d/tempid :db.part/user)
                :layer/name "Test Layer 2"
                :layer/fill "blue"}
               {:db/id (d/tempid :db.part/user)
                :layer/name "Test Layer 3"
                :layer/fill "green"}]))
