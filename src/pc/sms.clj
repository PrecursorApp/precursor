(ns pc.sms
  (:require [cemerick.url :as url]
            [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [pc.http.urls :as urls]
            [pc.profile :as profile]
            [pc.rollbar :as rollbar])
  (:import [com.google.common.base Throwables]))

(def twilio-sid "ACe47a9fc184b56645bddb7c3278d70adb")
(def twilio-auth-token "efc62c129d651a0a64fc809d4fe0c3bd")

(def twilio-phone-number "+19207538241")
(def base-url (format "https://api.twilio.com/2010-04-01/Accounts/%s/" twilio-sid))

(def send-agent (agent nil
                       :error-mode :continue
                       :error-handler (fn [a e]
                                        (println (Throwables/getStackTraceAsString e))
                                        (flush)
                                        (log/error e)
                                        (rollbar/report-exception e))))

(defn send-sms [phone-number body & {:keys [image-url]}]
  (http/post (str base-url "Messages.json")
             {:basic-auth [twilio-sid twilio-auth-token]
              :throw-exceptions false
              :form-params (merge {:From twilio-phone-number
                                   :To phone-number
                                   :Body body}
                                  (when (profile/register-twilio-callbacks?)
                                    {:StatusCallback (urls/twilio-status-callback)})
                                  (when image-url
                                    {:MediaUrl image-url}))}))
