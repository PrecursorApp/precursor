(ns pc.mixpanel
  (:refer-clojure :exclude [alias])
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clj-time.core :as time]
            [clj-time.coerce]
            [slingshot.slingshot :refer (throw+)]
            [pc.profile :as profile]
            [pc.util.base64 :as base64]))

(defonce mixpanel-agent (agent nil :error-mode :continue))

(def endpoint "http://api.mixpanel.com")

(defn api-token []
  (if (profile/prod-assets?)
    "31395b0272995b2e3b9d065ae69f8c6d"
    "7c5d0786b5c7543c1e837453fea0eb97"))

(defn api-key []
  (if (profile/prod-assets?)
    "cde52301ffe66b6709c615c56e2c57cd"
    "3b3e2e2fdaea93f13ac51c4b9e8a14f7"))

(defn api-secret []
  (if (profile/prod-assets?)
    "b529765506c7b8a12cbefc4c57aceec3"
    "bf5a36a9191db38c3beb6ccddc5d7340"))

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

(defn api-call* [_ uri data]
  (let [resp (http/post (str endpoint uri) {:query-params {:data (encode data)}})
        success? (-> resp :body (Integer/parseInt) (= 1))]
    (when (or (-> resp :status (not= 200)) (not success?))
      (throw+ resp))
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
