(ns pc.mixpanel
  (:refer-clojure :exclude [alias])
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clj-time.coerce]
            [clj-time.core :as time]
            [clojure.tools.logging :as log]
            [pc.profile :as profile]
            [pc.rollbar :as rollbar]
            [pc.util.base64 :as base64]
            [slingshot.slingshot :refer (throw+)])
  (:import [com.google.common.base Throwables]))

(defonce mixpanel-agent (agent nil
                               :error-mode :continue
                               :error-handler (fn [a e]
                                                (println (Throwables/getStackTraceAsString e))
                                                (flush)
                                                (log/error e)
                                                (rollbar/report-exception e))))

(def endpoint "http://api.mixpanel.com")

(def api-token profile/mixpanel-api-token)

(def api-key profile/mixpanel-api-key)

(def api-secret profile/mixpanel-api-secret)

(defn distinct-id-from-cookie [ring-req]
  (some-> ring-req
          :cookies
          (get (str "mp_" (api-token) "_mixpanel"))
          :value
          (json/decode)
          (get "distinct_id")))

(defn encode [data]
  (-> data
      (json/generate-string)
      (base64/encode)))

(defn ->mixpanel-date [datetime]
  (clj-time.format/unparse (clj-time.format/formatter "yyyy-MM-dd") datetime))

(defn ->full-mixpanel-date [datetime]
  (clj-time.format/unparse (clj-time.format/formatters :hour-minute-second) datetime))

(defn api-call* [_ uri data]
  (let [resp (http/post (str endpoint uri) {:query-params {:data (encode data)
                                                           :verbose 1}})
        success? (-> resp :body json/decode (get "status") (= 1))]
    (when (or (-> resp :status (not= 200)) (not success?))
      (throw+ (assoc resp :mixpanel-data data)))
    resp))

(defn api-call [url data]
  (send-off mixpanel-agent api-call* url data))

(defn track
  "Track an event."
  [event distinct-id & {:as properties}]
  (let [now (-> (time/now) (clj-time.coerce/to-long) (/ 1000) (int))
        data {:event event
              :properties (merge
                           {:token (api-token)
                            :time now
                            :event_time now
                            :distinct_id distinct-id}
                           properties)}]
    (api-call "/track" data)))

(defn alias
  "Alias the current user"
  [distinct-id alias]
  (track "$create_alias" distinct-id :alias alias))

(defn engage
  "Send Mixpanel 'people' data. data is a map containing one of '$set', '$set_once', '$add', '$append'
   optional properties can be passed by adding them as keys to data"
  [distinct-id data]
  (let [now (-> (time/now) (clj-time.coerce/to-long) (/ 1000) (int))
        data (merge data
                    {:$distinct_id distinct-id
                     :$token (api-token)
                     :$time now})]
    (api-call "/engage" data)))
