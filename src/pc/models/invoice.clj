(ns pc.models.invoice
  (:require [datomic.api :as d]
            [pc.datomic.web-peer :as web-peer])
  (:import [java.util UUID]))

(defn find-by-stripe-id [db stripe-id]
  (->> (d/datoms db :avet :invoice/stripe-id stripe-id)
    first
    :e
    (d/entity db)))

(defn find-by-team-and-client-part [db team client-part]
  (let [frontend-id (UUID. (:db/id team) client-part)
        candidate (some->> (d/datoms db :avet :frontend/id frontend-id)
                    first
                    :e
                    (d/entity db))]
    (when (:invoice/date candidate)
      candidate)))

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
