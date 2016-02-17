(ns pc.sms
  (:require [cemerick.url :as url]
            [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [pc.http.urls :as urls]
            [pc.profile :as profile]
            [pc.rollbar :as rollbar]
            [pc.twilio :as twilio])
  (:import [com.google.common.base Throwables]))

(defonce send-agent (agent nil
                           :error-mode :continue
                           :error-handler (fn [a e]
                                            (println (Throwables/getStackTraceAsString e))
                                            (flush)
                                            (log/error e)
                                            (rollbar/report-exception e))))

(defn send-sms [phone-number body & {:keys [image-url]}]
  (http/post (str (twilio/base-url) "Messages.json")
             {:basic-auth [(profile/twilio-sid) (profile/twilio-auth-token)]
              :throw-exceptions false
              :form-params (merge {:From (profile/twilio-phone-number)
                                   :To phone-number
                                   :Body body}
                                  (when (profile/register-twilio-callbacks?)
                                    {:StatusCallback (urls/twilio-status-callback)})
                                  (when image-url
                                    {:MediaUrl image-url}))}))

(defn async-send-sms [phone-number body & {:keys [image-url callback]}]
  (let [response-promise (promise)]
    (send-off send-agent (fn [a]
                           (let [resp (send-sms phone-number body :image-url image-url)]
                             (deliver response-promise resp)
                             (when callback (callback resp)))))
    response-promise))
