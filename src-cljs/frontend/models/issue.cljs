(ns frontend.models.issue
  (:require [clojure.set :as set]
            [datascript :as d]
            [frontend.utils :as utils :include-macros true]))

(defn find-by-frontend-id [db frontend-id]
  (d/entity db (:e (first (d/datoms db :avet :frontend/issue-id frontend-id)))))

(defn find-by-doc-id [db doc-id]
  (d/entity db (:e (first (d/datoms db :avet :issue/document doc-id)))))

(defn top-level-comment-ids [db issue-id]
  (let [all-ids (set (map :v (d/datoms db :aevt :issue/comments issue-id)))
        children-ids (set (d/q '{:find [[?e ...]]
                                 :in [$ ?issue-id]
                                 :where [[?issue-id :issue/comments ?e]
                                         [?e :comment/parent]]}
                               db issue-id))]
    (set/difference all-ids children-ids)))

(defn direct-descendant-ids [db comment-id]
  (set (map :e (d/datoms db :avet :comment/parent comment-id))))

(defn issue-score [issue cust time]
  (+ (count (:issue/votes issue))
     (if (= (:cust/email cust) (:issue/creator issue))
       10
       0)
     (condp < (- (.getTime time) (.getTime (:issue/created-at issue)))
       (* 1000 60 60 24 10) 0        ; 10 days
       (* 1000 60 60 24 5) 1         ; 5 days
       (* 1000 60 60 24 4) 2         ; 4 days
       (* 1000 60 60 24 3) 3         ; 3 days
       (* 1000 60 60 24 2) 5         ; 2 days
       (* 1000 60 60 24 1) 7         ; 1 days
       (* 1000 60 60 12) 10          ; 12 hours
       (* 1000 60 60) 20             ; 1 hour
       (* 1000 60 10) 40             ; 10 minutes
       100)))

(defn issue-comparator [cust time]
  (fn [issue-a issue-b]
    (let [score-res (compare (issue-score issue-b cust time)
                             (issue-score issue-a cust time))]
      (if (zero? score-res)
        (compare (:issue/created-at issue-b)
                 (:issue/created-at issue-a))
        score-res))))
