(ns pc.statsd
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clj-statsd :as s]
            [pc.profile :as profile]
            [pc.utils]
            [pc.version]))

(defn post-metric [type metric-name value & {:keys [source]}]
  (http/post "https://metrics-api.librato.com/v1/metrics"
             {:content-type :json
              :basic-auth [(profile/librato-api-name) (profile/librato-api-token)]
              :form-params {type
                            {metric-name
                             (merge {:value value}
                                    (when source
                                      {:source source}))}}}))

(defn post-annotation [annotation-stream & {:keys [title source description links start_time end_time]
                                            :as args}]
  (http/post (str "https://metrics-api.librato.com/v1/annotations/" annotation-stream)
             {:content-type :json
              :basic-auth [(profile/librato-api-name) (profile/librato-api-token)]
              :body (json/encode args)}))

(defn post-gauge [name value & {:keys [source]}]
  (post-metric "gauges" name value :source source))

(defn post-counter [name value & {:keys [source]}]
  (post-metric "counters" name value :source source))

;; Note: need something better when we have multiple servers
(defn send-deadman-ping-cron []
  (post-gauge "deadman" 1))

(defn notify-deployment []
  (let [version (pc.version/version)]
    (post-annotation "deploys"
                     :title (str version " deploy")
                     :links [{:rel "github"
                              :label "GitHub commit"
                              :href (str "https://github.com/dwwoelfel/precursor/commit/" version)}])))

(defn init []
  (s/setup (profile/statsd-host) 8125)
  (when (pc.profile/send-librato-events?)
    (pc.utils/straight-jacket
     (notify-deployment))
    (pc.utils/safe-schedule {:minute (range 0 60)} #'send-deadman-ping-cron)))
