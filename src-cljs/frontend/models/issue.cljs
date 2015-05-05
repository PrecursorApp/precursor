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
