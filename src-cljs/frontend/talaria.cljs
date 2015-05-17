(ns frontend.talaria
  (:require [cljs.core.async :as async]
            [frontend.utils :as utils]
            [goog.events :as gevents]
            [goog.net.WebSocket :as ws]
            [cognitect.transit :as transit])
  (:import [goog.net.WebSocket.EventType]))

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

(defonce debug-ws (atom nil))

(defn init [url]
  (let [;; creates class that will handle auto-reconnecting on failure
        w (goog.net.WebSocket.)
        _ (reset! debug-ws w)
        ch (async/chan (async/sliding-buffer 1024))
        tal-state (atom {:ws w
                         :open? false
                         :queued-msgs []
                         :msg-ch ch
                         :msg-mult (async/mult ch)})]
    (gevents/listen w goog.net.WebSocket.EventType.OPENED #(swap! tal-state assoc :open? true))
    (gevents/listen w goog.net.WebSocket.EventType.CLOSED #(swap! tal-state assoc :open? false))
    (gevents/listen w goog.net.WebSocket.EventType.ERROR #(swap! tal-state assoc :last-error %))
    (gevents/listen w goog.net.WebSocket.EventType.MESSAGE
                    #(do
                       (swap! tal-state assoc :last-message-time (js/Date.))
                       (async/put! ch (utils/inspect (decode-msg (.-message %))))))
    (.open w url)
    tal-state))

(defonce testing (init "ws://localhost:8080/talaria"))
