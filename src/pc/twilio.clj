(ns pc.twilio
  (:require [cemerick.url :as url]
            [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [pc.http.urls :as urls]
            [pc.rollbar :as rollbar]
            [pc.profile])
  (:import [com.google.common.base Throwables]))

(defn base-url []
  (format "https://api.twilio.com/2010-04-01/Accounts/%s/"
          (pc.profile/twilio-sid)))
