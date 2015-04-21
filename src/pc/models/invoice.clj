(ns pc.models.invoice
  (:require [datomic.api :as d]))

(defn find-by-stripe-id [db stripe-id]
  (->> (d/datoms db :avet :invoice/stripe-id stripe-id)
    first
    :e
    (d/entity db)))
