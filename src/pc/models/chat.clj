(ns pc.models.chat
  (:require [pc.datomic :as pcd]
            [datomic.api :refer [db q] :as d]))


(defn all [db]
  (pcd/touch-all '{:find [?t]
                   :where [[?t :chat/body]]}
                 db))

(defn find-by-document [db document]
  (pcd/touch-all '{:find [?t] :in [$ ?document-id]
                   :where [[?t :document/id ?document-id]
                           [?t :chat/body]]}
                 db (:db/id document)))

(defn find-chat-name [db cust-uuid]
  (ffirst (d/q '{:find [?name] :in [$ ?uuid]
                 :where [[?t :cust/uuid ?uuid]
                         [?t :cust/name ?name]]}
               db cust-uuid)))

(defn find-by-document [db document]
  (let [memo-find-name (memoize find-chat-name)]
    (map
     (fn [[chat-id]]
       (let [e (pcd/touch+ (d/entity db chat-id))]
         ;; TODO: teach the frontend how to lookup cust/name
         (assoc e :chat/cust-name (when-let [uuid (:cust/uuid e)]
                                    (memo-find-name db uuid)))))
     (d/q '{:find [?t] :in [$ ?document-id]
            :where [[?t :document/id ?document-id]]}
          db (:db/id document)))))

(comment
  (let [[doc-id & layer-ids] (pcd/generate-eids (pcd/conn) 4)]
    (d/transact (pcd/conn)
                [{:db/id doc-id
                  :document/name "Test Document 1"}
                 {:db/id (first layer-ids)
                  :document/id doc-id
                  :layer/name "Test Layer 1"
                  :layer/fill "red"}
                 {:db/id (second layer-ids)
                  :document/id doc-id
                  :layer/name "Test Layer 2"
                  :layer/fill "blue"}
                 {:db/id (last layer-ids)
                  :document/id doc-id
                  :layer/name "Test Layer 3"
                  :layer/fill "green"}])))
