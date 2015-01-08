(ns frontend.models.doc
  (:require [datascript :as d]
            [frontend.utils :as utils :include-macros true]))

(defn find-by-id [db id]
  (let [candidate (d/entity db id)]
    ;; faster than using a datalog query
    (when (:document/name candidate)
      candidate)))
