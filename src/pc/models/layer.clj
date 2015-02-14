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
  (map (partial d/entity db)
       (d/q '{:find [[?t ...]]
              :in [$ ?document-id]
              :where [[?t :document/id ?document-id]
                      [?t :layer/name]]}
            db (:db/id document))))

;; TODO: can use pull API here
(defn read-api [layer]
  (select-keys layer [:layer/name
                      :layer/uuid
                      :layer/type
                      :layer/start-x
                      :layer/start-y
                      :layer/end-x
                      :layer/end-y
                      :layer/rx
                      :layer/ry
                      :layer/fill
                      :layer/stroke-width
                      :layer/stroke-color
                      :layer/opacity
                      :layer/font-family
                      :layer/text
                      :layer/font-size
                      :layer/path
                      :layer/child
                      :layer/ui-id
                      :layer/ui-target
                      :frontend/id
                      :db/id ;; XXX: remove once frontend/id is finished
                      ]))
