(ns pc.statsd
  (:require [clj-http.client :as http]
            [clj-statsd :as s]
            [pc.profile :as profile]
            [pc.utils]))

(def librato-api-name "daniel+start-trial@prcrsr.com")
(def librato-api-token "d6f799f643c673a6eb2f555b78362324f6a39a0780fe03a5fff9c58a9d6c145f")

(defn post-metric [type metric-name value & {:keys [source]}]
  (http/post "https://metrics-api.librato.com/v1/metrics"
             {:content-type :json
              :basic-auth [librato-api-name librato-api-token]
              :form-params {type
                            {metric-name
                             (merge {:value value}
                                    (when source
                                      {:source source}))}}}))

(defn post-gauge [name value & {:keys [source]}]
  (post-metric "gauges" name value :source source))

(defn post-counter [name value & {:keys [source]}]
  (post-metric "counters" name value :source source))

;; Note: need something better when we have multiple servers
(defn send-deadman-ping-cron []
  (post-gauge "deadman" 1))

(defn init []
  (s/setup (profile/statsd-host) 8125)
  (pc.utils/safe-schedule {:minute (range 0 60)} #'send-deadman-ping-cron))
