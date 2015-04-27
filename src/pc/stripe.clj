(ns pc.stripe
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clj-time.core :as time]
            [clj-time.coerce]
            [clojure.set :as set]
            [clojure.tools.logging :as log]
            [pc.profile :as profile]
            [pc.utils :as utils]
            [pc.util.date :as date-util]
            [slingshot.slingshot :refer (try+ throw+)]))

;; Prerequisites:
;;  clj-http: https://github.com/dakrone/clj-http
;;  Stripe API: https://stripe.com/docs/api/curl

;; XXX: live keys from env vars
(def dev-secret-key "sk_test_STWXh4dEaDLn3FFVJVnnZBQF")
(defn secret-key []
  (let [env-key (System/getenv "STRIPE_SECRET_KEY")]
    (when (profile/prod?)
      (assert env-key "Have to provide an STRIPE_SECRET_KEY in prod!"))
    (or env-key
        dev-secret-key)))

(def dev-publishable-key "pk_test_EggMOrfTt155yQVE4IvpN9sy")
(defn publishable-key []
  (let [env-key (System/getenv "STRIPE_PUBLISHABLE_KEY")]
    (when (profile/prod?)
      (assert env-key "Have to provide an STRIPE_PUBLISHABLE_KEY in prod!"))
    (or env-key
        dev-publishable-key)))

(def card-translation
  {"exp_year" :credit-card/exp-year
   "exp_month" :credit-card/exp-month
   "last4" :credit-card/last4
   "brand" :credit-card/brand
   "fingerprint" :credit-card/fingerprint
   "id" :credit-card/stripe-id})

(defn card-api->model [card-fields]
  (-> card-fields
    (select-keys (keys card-translation))
    (set/rename-keys card-translation)))

(defn timestamp->model [timestamp]
  (-> timestamp
    (* 1000)
    (clj-time.coerce/from-long)
    (clj-time.coerce/to-date)))

(defn discount-api->model [discount-fields]
  {:discount/start (timestamp->model (get discount-fields "start"))
   :discount/end (timestamp->model (get discount-fields "end"))
   :discount/coupon {:coupon/stripe-id (get-in discount-fields ["coupon" "id"])}})

(def invoice-translation
  {"total" :invoice/total
   "paid" :invoice/paid?
   "id" :invoice/stripe-id
   "subtotal" :invoice/subtotal
   "attempted" :invoice/attempted?
   "date" :invoice/date
   "next_payment_attempt" :invoice/next-payment-attempt
   "description" :invoice/description
   "period_start" :invoice/period-start
   "period_end" :invoice/period-end})

(defn invoice-api->model [api-fields]
  (-> api-fields
    (select-keys (keys invoice-translation))
    (set/rename-keys invoice-translation)
    (utils/remove-map-nils)
    (utils/update-when-in [:invoice/date] timestamp->model)
    (utils/update-when-in [:invoice/next-payment-attempt] timestamp->model)
    (utils/update-when-in [:invoice/period-start] timestamp->model)
    (utils/update-when-in [:invoice/period-end] timestamp->model)))

(def invoice-item-translation
  {"amount" :line-item/amount
   "id" :line-item/stripe-id
   "description" :line-item/description
   "date" :line-item/date})

(defn invoice-item->model [api-fields]
  (-> api-fields
    (select-keys (keys invoice-item-translation))
    (set/rename-keys invoice-item-translation)
    (utils/remove-map-nils)
    (utils/update-when-in [:line-item/date] timestamp->model)))

(def base-url "https://api.stripe.com/v1/")

(defn api-call [method endpoint & [params]]
  (-> (http/request (merge {:method method
                            :url (str base-url endpoint)
                            :basic-auth [(secret-key) ""]}
                           params))
    :body
    json/decode))

(defn create-customer [token-id plan trial-end & {:keys [coupon-code email metadata description]}]
  (let [earliest-timestamp (date-util/timestamp-sec (time/plus (time/now) (time/hours 1)))
        trial-end-timestamp (date-util/timestamp-sec trial-end)]
    (api-call :post "customers" {:form-params (merge {:source token-id
                                                      :plan plan}
                                                     (when (> trial-end-timestamp earliest-timestamp)
                                                       {:trial_end trial-end-timestamp})
                                                     (when coupon-code
                                                       {:coupon coupon-code})
                                                     (when email
                                                       {:email email})
                                                     (when metadata
                                                       {:metadata (json/encode metadata)})
                                                     (when description
                                                       {:description description}))})))

(defn update-card [customer-id token-id]
  (api-call :post (str "customers/" customer-id)
            {:form-params {:source token-id}}))

(defn update-quantity [customer-id subscription-id quantity]
  (api-call :post (str "customers/" customer-id "/subscriptions/" subscription-id)
            {:form-params {:quantity quantity}}))

(defn create-invoice [customer-id & {:keys [description]}]
  (api-call :post "invoices" {:form-params (merge {:customer customer-id}
                                                  (when description
                                                    {:description description}))}))

(defn fetch-customer [customer-id]
  (api-call :get (str "customers/" customer-id)))

(defn fetch-subscription [customer-id subscription-id]
  (api-call :get (str "customers/" customer-id "/subscriptions/" subscription-id)))

(defn fetch-event [event-id]
  (api-call :get (str "events/" event-id)))

(defn fetch-events [& {:keys [limit ending-before]
                       :or {limit 100}}]
  (api-call :get "events" {:query-params (merge
                                          {:limit limit}
                                          (when ending-before
                                            {:ending_before ending-before}))}))

(defn ensure-plans []
  (try+
   (api-call :post
             "plans"
             {:form-params {:id "team"
                            :amount (* 100 10) ; $10
                            :currency "usd"
                            :interval "month"
                            :name "Team subscription"}})
   (catch [:status 400] e
     (if (some-> e :body json/decode (get-in ["error" "message"]) (= "Plan already exists."))
       (log/infof "team plan already exists in Stripe")
       (throw+)))))

(defn ensure-coupons []
  (try+
   (api-call :post
             "coupons"
             {:form-params {:id "product-hunt"
                            :percent_off "50"
                            :duration "repeating"
                            :duration_in_months 6}})
   (catch [:status 400] e
     (if (some-> e :body json/decode (get-in ["error" "message"]) (= "Coupon already exists."))
       (log/infof "product-hunt coupon already exists in Stripe")
       (throw+)))))

(defn init []
  (ensure-plans)
  (ensure-coupons))
