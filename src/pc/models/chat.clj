(ns pc.models.chat
  (:require [datomic.api :refer [db q] :as d]
            [pc.datomic :as pcd]
            [pc.datomic.web-peer :as web-peer]
            [pc.utils :as utils]))


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
              :where [[?t :document/id ?document-id]
                      [?t :chat/body]]}
            db (:db/id document))))

;; TODO: move cust-name lookup into here
(defn read-api [chat]
  (-> chat
    (select-keys [:document/id
                  :server/timestamp
                  :client/timestamp
                  :session/uuid
                  :chat/body
                  :chat/color
                  :chat/document
                  :cust/uuid])
    (utils/update-when-in [:chat/document] :db/id)
    (assoc :db/id (web-peer/client-id chat))))
