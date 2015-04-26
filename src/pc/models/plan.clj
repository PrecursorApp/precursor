(ns pc.models.plan
  (:require [clj-time.core :as time]
            [clj-time.coerce]
            [datomic.api :as d]
            [pc.datomic :as pcd]
            [pc.datomic.web-peer :as web-peer]
            [pc.models.invoice :as invoice-model]
            [pc.utils :as utils]))

;; How coupons work on a plan:
;;  1. plan gets plan/coupon-code on trial start
;;  2. When the stripe plan is created, plan/coupon-code is removed
;;     and replaced with a discount (which has a coupon)
;;  3. The discount has an end-date, after which it's no longer applied
;;  4. Discount mirrors what is up in Stripe

(defn find-by-stripe-customer [db stripe-customer]
  (d/entity db (d/q '[:find ?e . :in $ ?c :where [?e :plan/stripe-customer-id ?c]]
                    db stripe-customer)))

;; Only use for debugging. In code, always go from team -> plan
(defn find-by-subdomain [db subdomain]
  (d/entity db (d/q '[:find ?e . :in $ ?sub :where [?t :team/subdomain ?sub] [?t :team/plan ?e]] db subdomain)))

(def coupons
  #{{:coupon/stripe-id "product-hunt"
     :coupon/percent-off 50
     :coupon/duration-in-months 6
     :db/ident :coupon/product-hunt}})

(defn coupon-by-code [coupon-code]
  (first (filter #(= (:coupon/stripe-id %) coupon-code) coupons)))

(defn coupon-read-api [coupon-ident]
  (first (filter #(= (:db/ident %) coupon-ident) coupons)))

(defn read-api [plan]
  (-> plan
    (select-keys [:plan/start
                  :plan/trial-end
                  :plan/credit-card
                  :plan/paid?
                  :plan/billing-email
                  :plan/active-custs
                  :plan/account-balance
                  :plan/next-period-start
                  :discount/start
                  :discount/end
                  :discount/coupon
                  :credit-card/exp-year
                  :credit-card/exp-month
                  :credit-card/last4
                  :credit-card/brand
                  :plan/invoices])
    (utils/update-when-in [:discount/coupon] coupon-read-api)
    (update-in [:plan/active-custs] #(set (map :cust/email %)))
    (utils/update-when-in [:plan/invoices] #(map invoice-model/read-api %))
    (assoc :db/id (web-peer/client-id plan))))

(defn trial-over? [plan & {:keys [now]
                           :or {now (time/now)}}]
  (time/after? now
               (clj-time.coerce/from-date (:plan/trial-end plan))))

(defn coupon-exists? [db stripe-id]
  (seq (d/q '[:find ?e :in $ ?stripe-id :where [?e :coupon/stripe-id ?stripe-id]] db stripe-id)))

;; only adds new coupons, deleted or modified coupons need to be handled manually
(defn ensure-coupons []
  (when-not (= (set (map :coupon/stripe-id coupons))
               (set (d/q '[:find [?id ...]
                           :where [_ :coupon/stripe-id ?id]]
                         (pcd/default-db))))
    @(d/transact (pcd/conn) (map #(assoc % :db/id (d/tempid :db.part/user)) coupons))))

(defn init []
  (ensure-coupons))
