(ns frontend.models.chat
  (:require [datascript :as d]
            [frontend.db.trans :as trans]
            [frontend.datetime :as datetime]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]))

(defn find-count [db]
  (count (d/datoms db :aevt :chat/body)))

(defn chat-timestamps-since [db time]
  (map first
       (d/q '{:find [?server-timestamp]
              :in [$ ?last-time]
              :with [?t]
              :where [[?t :chat/body]
                      [?t :server/timestamp ?server-timestamp]
                      [(> ?server-timestamp ?last-time)]]}
            db time)))

(defn compute-unread-chat-count [db last-read-time]
  (if-not last-read-time
    (find-count db)
    (count (chat-timestamps-since db last-read-time))))

(defn display-name [chat sente-id]
  (or (:chat/cust-name chat)
      (if (= (str (:session/uuid chat))
             sente-id)
        "You"
        (apply str (take 6 (str (:session/uuid chat)))))))

(defn create-bot-chat [conn app-state body & [extra-fields]]
  (d/transact! conn [(merge {:db/id (trans/get-next-transient-id conn)
                             :chat/body body
                             :chat/document (:document/id app-state)
                             :client/timestamp (datetime/server-date)
                             :server/timestamp (datetime/server-date)
                             :cust/uuid (:cust/uuid state/subscriber-bot)}
                            extra-fields)]
               {:bot-layer true}))
