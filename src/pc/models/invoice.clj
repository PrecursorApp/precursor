(ns pc.models.invoice
  (:require [datomic.api :as d]
            [pc.datomic.web-peer :as web-peer]))

(defn find-by-stripe-id [db stripe-id]
  (->> (d/datoms db :avet :invoice/stripe-id stripe-id)
    first
    :e
    (d/entity db)))

(defn read-api [invoice]
  (-> invoice
    (select-keys [:invoice/subtotal
                  :invoice/total
                  :invoice/date
                  :invoice/paid?
                  :invoice/attempted?
                  :invoice/next-payment-attempt
                  :invoice/description
                  :discount/coupon
                  :discount/start
                  :discount/end])
    (assoc :db/id (web-peer/client-id invoice))))
