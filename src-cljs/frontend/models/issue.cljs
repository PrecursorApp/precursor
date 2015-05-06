(ns frontend.models.issue
  (:require [datascript :as d]
            [frontend.utils :as utils :include-macros true]))

(defn top-level-comments [issue]
  (remove :comment/parent (:issue/comments issue)))

(defn direct-descendants [db comment]
  (d/q '{:find [[?e ...]]
         :in [$ ?comment-id]
         :where [[?e :comment/parent ?comment-id]]}
       db (:db/id comment)))

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
       30)))

(defn issue-comparator [cust time]
  (fn [issue-a issue-b]
    (compare (issue-score issue-b cust time)
             (issue-score issue-a cust time))))
