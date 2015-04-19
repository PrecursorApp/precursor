(ns pc.models.plan
  (:require [clj-time.core :as time]
            [clj-time.coerce]
            [pc.datomic.web-peer :as web-peer]))

(defn read-api [plan]
  (-> plan
    (select-keys [:plan/start
                  :plan/trial-end
                  :plan/credit-card
                  :plan/paid?
                  :plan/billing-email
                  :plan/active-custs
                  :credit-card/exp-year
                  :credit-card/exp-month
                  :credit-card/last4
                  :credit-card/brand])
    (update-in [:plan/active-custs] #(set (map :cust/email %)))
    (assoc :db/id (web-peer/client-id plan))))

(defn trial-over? [plan & {:keys [now]
                           :or {now (time/now)}}]
  (time/after? now
               (clj-time.coerce/from-date (:plan/trial-end plan))))
