(ns pc.models.issue
  (:require [clojure.set :as set]
            [pc.datomic :as pcd]
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
                  :comment/parent
                  :frontend/issue-id])
    (utils/update-when-in [:comment/parent] (fn [p] {:frontend/issue-id (:frontend/issue-id p)}))
    (utils/update-when-in [:comment/author] :cust/email)))

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

(defn valid-issue? [?issue]
  (let [actual-keys (set (keys (d/touch ?issue)))
        optional-keys #{:issue/comments :issue/votes :issue/document :issue/description :db/id}
        required-keys #{:issue/title :issue/author :issue/created-at :frontend/issue-id}]
    (and (set/subset? required-keys actual-keys)
         (set/subset? actual-keys (set/union optional-keys required-keys)))))

(defn valid-comment? [?comment]
  (let [actual-keys (set (keys (d/touch ?comment)))
        optional-keys #{:comment/parent :db/id}
        required-keys #{:comment/body :comment/author :comment/created-at :frontend/issue-id}]
    (and (set/subset? required-keys actual-keys)
         (set/subset? actual-keys (set/union optional-keys required-keys)))))

(defn valid-vote? [?vote]
  (let [actual-keys (set (keys (d/touch ?vote)))
        optional-keys #{:db/id}
        required-keys #{:vote/cust :vote/cust-issue :frontend/issue-id}]
    (and (set/subset? required-keys actual-keys)
         (set/subset? actual-keys (set/union optional-keys required-keys)))))

(defn valid-document? [?doc]
  (let [actual-keys (set (keys (d/touch ?doc)))
        optional-keys #{:db/id}
        required-keys #{:document/name :document/creator :document/privacy}]
    (and (set/subset? required-keys actual-keys)
         (set/subset? actual-keys (set/union optional-keys required-keys)))))
