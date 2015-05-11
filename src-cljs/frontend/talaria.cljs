(ns frontend.talaria
  (:require [cljs.core.async :as async]
            [frontend.utils :as utils]
            [goog.net.WebSocket :as ws]
            [cognitect.transit :as transit]))

(defn decode-msg [msg]
  (let [r (transit/reader :json)]
    (transit/read r msg)))

(defn encode-msg [msg]
  (let [r (transit/writer :json)]
    (transit/write r msg)))

(defn send-msg [tal-state [op data] & [timeout-ms callback]]
  (let [callback-uuid (when callback (utils/uuid))]
    (when callback
      ())
    (swap! tal-state conj :queued-msgs (merge {:op op
                                               :data data}
                                              (when callback-uuid
                                                {:talaria/callback-uuid callback-uuid})))))

(defn init [url]
  (let [w (goog.net.WebSocket.)
        tal-state (atom {:ws w
                         :open? false
                         :queued-msgs []})]

    ))
