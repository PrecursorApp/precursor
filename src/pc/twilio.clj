(ns pc.twilio
  (:require [cemerick.url :as url]
            [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [pc.http.urls :as urls]
            [pc.rollbar :as rollbar])
  (:import [com.google.common.base Throwables]))

(def sid "ACe47a9fc184b56645bddb7c3278d70adb")
(def auth-token "efc62c129d651a0a64fc809d4fe0c3bd")

(def phone-number "+19207538241")
(def base-url (format "https://api.twilio.com/2010-04-01/Accounts/%s/" sid))
