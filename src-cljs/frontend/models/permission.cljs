(ns frontend.models.permission
  (:require [datascript :as d]
            [frontend.utils :as utils]))

(defn github-permissions [db doc]
  (filter #(= (:db/id doc) (:permission/document %))
          (map #(d/entity db (:e %))
               (d/datoms db :avet :permission/reason :permission.reason/github-markdown))))
