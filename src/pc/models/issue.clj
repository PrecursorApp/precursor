(ns pc.models.issue
  (:require [pc.datomic :as pcd]
            [pc.utils :as utils]
            [datomic.api :refer [db q] :as d]))

(defn all-issues [db]
  (map #(d/entity db (:e %)) (d/datoms db :aevt :issue/title)))

(defn all-votes [db]
  (map #(d/entity db (:e %)) (d/datoms db :aevt :vote/cust)))

(defn find-by-frontend-id [db frontend-id]
  (d/entity db (:e (first (d/datoms db :avet :frontend/issue-id frontend-id)))))

(defn vote-read-api [vote]
  (-> vote
    (select-keys [:vote/cust
                  :frontend/issue-id])
    (update-in [:vote/cust] :cust/email)))

(defn comment-read-api [comment]
  (-> comment
    (select-keys [:comment/body
                  :comment/author
                  :comment/created-at
                  :comment/frontend-id
                  :comment/parent
                  :frontend/issue-id])
    (utils/update-when-in [:comment/parent] :frontend/issue-id)))

(defn read-api [issue]
  (-> issue
    (select-keys [:issue/title
                  :issue/description
                  :issue/author
                  :issue/document
                  :issue/created-at
                  :issue/votes
                  :issue/comments
                  :frontend/issue-id])
    (utils/update-when-in [:issue/document] :db/id)
    (utils/update-when-in [:issue/author] :cust/email)
    (utils/update-when-in [:issue/votes] #(set (map vote-read-api %)))
    (utils/update-when-in [:issue/comments] #(set (map comment-read-api %)))))
