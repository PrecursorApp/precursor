(ns pc.models.plan
  (:require [pc.datomic.web-peer :as web-peer]))

(defn read-api [plan]
  (-> plan
    (select-keys [:plan/start
                  :plan/trial-end
                  :plan/credit-card
                  :plan/paid?
                  :plan/billing-email
                  :credit-card/exp-year
                  :credit-card/exp-month
                  :credit-card/last4
                  :credit-card/brand])
    (assoc :db/id (web-peer/client-id plan))))
