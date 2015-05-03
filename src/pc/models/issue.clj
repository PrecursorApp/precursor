(ns pc.models.issue
  (:require [pc.datomic :as pcd]
            [pc.utils :as utils]
            [datomic.api :refer [db q] :as d]))

(defn all [db]
  (map #(d/entity db (:e %)) (d/datoms db :aevt :issue/frontend-id)))

(defn vote-read-api [vote]
  (-> vote
    (select-keys [:vote/cust
                  :vote/frontend-id])
    (update-in [:vote/cust] :cust/email)))

(defn comment-read-api [comment]
  (-> comment
    (select-keys [:comment/body
                  :comment/cust
                  :comment/created-at
                  :comment/frontend-id
                  :comment/parent])
    (utils/update-when-in [:comment/parent] :comment/frontend-id)))

(defn read-api [issue]
  (-> issue
    (select-keys [:issue/title
                  :issue/description
                  :issue/author
                  :issue/document
                  :issue/created-at
                  :issue/frontend-id
                  :issue/votes
                  :issue/comments])
    (utils/update-when-in [:issue/document] :db/id)
    (utils/update-when-in [:issue/author] :cust/email)
    (utils/update-when-in [:issue/votes] #(set (map vote-read-api %)))
    (utils/update-when-in [:issue/comments] #(set (map comment-read-api %)))))
