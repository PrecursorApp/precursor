(ns frontend.models.doc
  (:require [clojure.string :as str]
            [datascript :as d]
            [frontend.utils :as utils :include-macros true]))

(defn find-by-id [db id]
  ;; may regret this later, but we may know about a doc-id before we store it in the db
  (or (d/entity db id) {:db/id id}))
