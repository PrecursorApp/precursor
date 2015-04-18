(ns pc.stripe
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clj-time.core :as time]
            [clojure.set :as set]
            [pc.util.date :as date-util]))

;; Prerequisites:
;;  clj-http: https://github.com/dakrone/clj-http
;;  Stripe API: https://stripe.com/docs/api/curl

;; XXX: live keys from env vars
(defn secret-key []
  "sk_test_STWXh4dEaDLn3FFVJVnnZBQF")

(defn publishable-key []
  "pk_test_EggMOrfTt155yQVE4IvpN9sy")

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

(defn fetch-customer [customer-id]
  (api-call :get (str "customers/" customer-id)))

(defn fetch-subscription [customer-id subscription-id]
  (api-call :get (str "customers/" customer-id "/subscriptions/" subscription-id)))

(defn fetch-event [event-id]
  (api-call :get (str "events/" event-id)))
