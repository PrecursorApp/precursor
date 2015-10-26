(ns pc.models.chat-bot
  (:require [pc.datomic :as pcd]
            [clojure.set :as set]
            [datomic.api :refer [db q] :as d]))

;; make sure to update frontend, when changing this list (sorry :()
;; frontend.db/generate-chat-bots
(def chat-bots [{:chat-bot/name "daniel"}
                {:chat-bot/name "danny"}
                {:chat-bot/name "prcrsr"}])

(defn read-api [chat-bot]
  (select-keys chat-bot [:chat-bot/name]))


(defn init []
  (when-not (set/subset? (set (map :chat-bot/name chat-bots))
                         (set (map first (d/q '[:find ?name
                                                :where [_ :chat-bot/name ?name]]
                                              (pcd/default-db)))))
    (d/transact (pcd/conn) (map #(assoc % :db/id (d/tempid :db.part/user)) chat-bots))))
