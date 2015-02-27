(ns pc.models.chat
  (:require [pc.datomic :as pcd]
            [pc.datomic.web-peer :as web-peer]
            [datomic.api :refer [db q] :as d]))


(defn all [db]
  (pcd/touch-all '{:find [?t]
                   :where [[?t :chat/body]]}
                 db))

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
         (let [name (when-let [uuid (:cust/uuid e)]
                      (memo-find-name db uuid))]
           (if name
             (assoc e :chat/cust-name name)
             e))))
     (d/q '{:find [?t] :in [$ ?document-id]
            :where [[?t :document/id ?document-id]
                    [?t :chat/body]]}
          db (:db/id document)))))

;; TODO: move cust-name lookup into here
(defn read-api [chat]
  (-> chat
    (select-keys [:document/id
                  :server/timestamp
                  :client/timestamp
                  ;; TODO: teach frontend to lookup cust/name
                  :cust/cust-name
                  :chat/body
                  :chat/color
                  :chat/cust-name])
    (assoc :db/id (web-peer/client-id chat))))
