(ns pc.slack
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [pc.utils :as utils]))

;; purposely not giving this a buffer, we want it to explode if it gets full
;; b/c by that time it's already f'd. Need a better solution for this problem.
(defonce slack-ch (async/chan))

(defn send-slack-webhook [slack-hook slack-data]
  (http/post (:slack-hook/webhook-url slack-hook) {:form-params {"payload" (json/encode slack-data)}}))

(defn queue-slack-webhook [slack-hook slack-data]
  (async/put! slack-ch {:slack-hook slack-hook :slack-data slack-data}))

(defn start-slack-consumers [consumer-count]
  (dotimes [x consumer-count]
    (async/go-loop []
      (when-let [{:keys [slack-hook slack-data]} (async/<! slack-ch)]
        (utils/with-report-exceptions
          (log/infof "sending slack webhook for hook id %s to %s" (:db/id slack-hook) (:slack-hook/channel-name slack-hook))
          (send-slack-webhook slack-hook slack-data))
        (recur)))))

(defn init []
  (start-slack-consumers 2))
