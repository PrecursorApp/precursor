(ns frontend.stripe
  (:require [cljs.core.async :as async]
            [frontend.config :as config]
            [frontend.utils :as utils]
            [goog.net.jsloader])
  (:require-macros [cljs.core.async.macros :refer [go]]))

;; Prerequisites:
;;  Stripe's Checkout.js: https://stripe.com/docs/checkout#integration-custom
;;  core.async: https://github.com/clojure/core.async

(defn checkout-loaded? []
  js/window.StripeCheckout)

(defn load-checkout []
  (let [ch (async/promise-chan)]
    (.addCallback (goog.net.jsloader/load "https://checkout.stripe.com/checkout.js")
                  #(async/put! ch :stripe-checkout-loaded))
    ch))

(defn checkout-config [token-callback close-callback email]
  {:key config/stripe-publishable-key
   :token token-callback
   :image "/img/precursor-logo.png"
   :name "Precursor"
   :description "Paid subscription"
   :panelLabel "Start plan"
   :email email
   :closed close-callback})

(defn open-checkout [email token-callback close-callback & [extra-config]]
  (go
    (when-not (checkout-loaded?)
      (async/<! (load-checkout)))
    (-> (checkout-config token-callback
                         close-callback
                         email)
      (merge extra-config)
      clj->js
      (js/window.StripeCheckout.configure)
      (.open))))
