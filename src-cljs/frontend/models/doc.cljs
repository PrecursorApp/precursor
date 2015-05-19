(ns frontend.models.doc
  (:require [clojure.string :as str]
            [datascript :as d]
            [frontend.utils :as utils :include-macros true]))

(defn urlify-doc-name [doc-name]
  (-> doc-name
    (str/replace #"[^A-Za-z0-9-_]+" "-")
    (str/replace #"^-" "")
    (str/replace #"-$" "")))

(defn find-by-id [db id]
  (let [candidate (d/entity db id)]
    ;; faster than using a datalog query
    (when (:document/name candidate)
      candidate)))
