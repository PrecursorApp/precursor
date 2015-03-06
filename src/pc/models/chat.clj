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
  (map (partial d/entity db)
       (d/q '{:find [[?t ...]]
              :in [$ ?document-id]
              :where [[?t :chat/document ?document-id]
                      [?t :chat/body]]}
            db (:db/id document))))

;; TODO: move cust-name lookup into here
(defn read-api [chat]
  (-> chat
    (select-keys [:server/timestamp
                  :client/timestamp
                  :session/uuid
                  :chat/body
                  :chat/color
                  :chat/document
                  ;; TODO: remove when frontend is deployed
                  :document/id
                  :cust/uuid])
    (update-in [:chat/document] :db/id)
    (assoc :db/id (web-peer/client-id chat))))
