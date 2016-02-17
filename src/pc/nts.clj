(ns pc.nts
  (:require [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [clj-http.client :as http]
            [clj-time.core :as time]
            [clj-time.format]
            [pc.profile :as profile]
            [pc.twilio :as twilio]
            [pc.rollbar :as rollbar]
            [pc.utils]
            [slingshot.slingshot :refer (try+ throw+)])
  (:import [java.util.concurrent LinkedBlockingQueue]
           [com.google.common.base Throwables]))

(defn fetch-token []
  (-> (http/post (str (twilio/base-url) "Tokens.json")
                 {:basic-auth [(profile/twilio-sid) (profile/twilio-auth-token)]})
    :body
    cheshire.core/decode
    (#(assoc % :expires (time/plus (clj-time.format/parse (get % "date_created"))
                                   (time/seconds (Integer/parseInt (get % "ttl"))))))))

(defonce token (atom nil))

(defn get-ice-servers []
  (get @token "ice_servers"))

(defn replace-token []
  (log/info "retrieving new ice servers from Twilio")
  (let [new-token (fetch-token)]
    (reset! token new-token)))

(defn init []
  (log/infof "retrieving ice servers from Twilio")
  (reset! token (fetch-token))
  (pc.utils/safe-schedule {:minute [2]} #'replace-token))
