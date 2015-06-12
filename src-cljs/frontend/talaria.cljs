(ns frontend.talaria
  (:require [cljs.core.async :as async]
            [frontend.utils :as utils]
            [goog.events :as gevents]
            [goog.net.WebSocket :as ws]
            [cognitect.transit :as transit])
  (:import [goog.net.WebSocket.EventType])
  (:require-macros [cljs.core.async.macros :refer (go-loop)]))

(defn decode-msg [msg]
  (let [r (transit/reader :json)]
    (transit/read r msg)))

(defn encode-msg [msg]
  (let [r (transit/writer :json)]
    (transit/write r msg)))

(defn pop-callback [tal-state cb-uuid]
  (loop [val @tal-state]
    (if (compare-and-set! tal-state val (update-in val [:callbacks] dissoc cb-uuid))
      (get-in val [:callbacks cb-uuid])
      (recur @tal-state))))

(defn run-callback [tal-state cb-uuid cb-data]
  (when-let [cb-fn (pop-callback tal-state cb-uuid)]
    (cb-fn cb-data)))

(defn queue-msg [tal-state msg & [timeout-ms callback]]
  (let [queue (:send-queue @tal-state)
        cb-uuid (when callback (utils/uuid))]
    (when callback
      (swap! tal-state assoc-in [:callbacks cb-uuid] callback)
      (js/setTimeout #(run-callback tal-state cb-uuid {:tal/error :tal/timeout
                                                       :tal/status :error})
                     timeout-ms))
    (swap! queue conj (merge msg
                             (when callback
                               {:tal/cb-uuid cb-uuid})))))

(defn pop-atom [a]
  (loop [val @a]
    (if (compare-and-set! a val (subvec val (min 1 (count val))))
      (first val)
      (recur @a))))

(defn send-msg [ws msg]
  (.send ws (encode-msg msg)))

(defn consume-send-queue [tal-state]
  (let [send-queue (:send-queue @tal-state)
        ws (:ws @tal-state)]
    (loop [msg (pop-atom send-queue)]
      (when msg
        (send-msg ws msg)
        (swap! tal-state assoc :last-send-time (js/Date.))
        (recur (pop-atom send-queue))))))

(defn start-send-queue [tal-state]
  (let [send-queue (:send-queue @tal-state)]
    (add-watch send-queue ::send-watcher (fn [_ _ old new]
                                           (when (> (count new) (count old))
                                             (consume-send-queue tal-state))))
    (consume-send-queue tal-state)))

(defn shutdown-send-queue [tal-state]
  (remove-watch (:send-queue @tal-state) ::send-watcher))

;; XXX: handle special messages from the server (ping, close)
(defn consume-recv-queue [tal-state handler]
  (let [recv-queue (:recv-queue @tal-state)]
    (loop [msg (pop-atom recv-queue)]
      (when msg
        (if (keyword-identical? :tal/reply (:op msg))
          (run-callback tal-state (:tal/cb-uuid msg) (:data msg))
          (handler msg))
        (recur (pop-atom recv-queue))))))

(defn start-recv-queue [tal-state handler]
  (let [recv-queue (:recv-queue @tal-state)]
    (add-watch recv-queue ::recv-watcher (fn [_ _ old new]
                                           (when (> (count new) (count old))
                                             (consume-recv-queue tal-state handler))))
    (consume-recv-queue tal-state handler)))

(defn start-ping [tal-state]
  (let [ms (:keep-alive-ms @tal-state)]
    (js/window.setInterval (fn []
                             (let [last-send (:last-send-time @tal-state)]
                               (when (or (not last-send)
                                         (<= (/ ms 2) (- (.getTime (js/Date.)) (.getTime last-send))))
                                 (queue-msg tal-state {:op :tal/ping}))))
                           (/ ms 2))))

(defn init
  "Guaranteed to run keep-alive every keep-alive-ms interval, but may probably run
   about twice as often"
  [url & {:keys [on-open on-close on-error keep-alive-ms]
          :or {keep-alive-ms 60000}}]

  (let [ ;; creates class that will handle auto-reconnecting on failure
        w (goog.net.WebSocket.)
        ch (async/chan (async/sliding-buffer 1024))
        send-queue (atom [])
        recv-queue (atom [])
        tal-state (atom {:ws w
                         :keep-alive-ms keep-alive-ms
                         :open? false
                         :send-queue send-queue
                         :recv-queue recv-queue
                         :callbacks {}})]
    (gevents/listen w goog.net.WebSocket.EventType.OPENED
                    #(do
                       (utils/mlog "opened" %)
                       (let [timer-id (start-ping tal-state)]
                         (swap! tal-state (fn [s]
                                            (-> s
                                              (assoc :open? true
                                                     :keep-alive-timer timer-id)
                                              (dissoc :closed? :close-code :close-reason)))))
                       (start-send-queue tal-state)

                       (when (fn? on-open)
                         (on-open tal-state))))
    (gevents/listen w goog.net.WebSocket.EventType.CLOSED
                    #(do
                       (utils/mlog "closed" %)
                       (swap! tal-state assoc
                              :open? false
                              :closed? true
                              :close-code (.-code %)
                              :close-reason (.-reason %))
                       (js/clearInterval (:keep-alive-timer @tal-state))
                       (when (fn? on-close)
                         (on-close tal-state {:code (.-code %)
                                              :reason (.-reason %)}))))
    (gevents/listen w goog.net.WebSocket.EventType.ERROR
                    #(do (utils/mlog "error" %)
                         (swap! tal-state assoc :last-error-time (js/Date.))
                         (when (fn? on-error)
                           (on-error tal-state))))
    (gevents/listen w goog.net.WebSocket.EventType.MESSAGE
                    #(do
                       ;;(utils/mlog "message" %)
                       (swap! tal-state assoc :last-recv-time (js/Date.))
                       (swap! recv-queue (fn [q] (apply conj q (decode-msg (.-message %)))))))
    (.open w url)
    tal-state))

(defn shutdown [tal-state]
  (.close (:ws @tal-state))
  (shutdown-send-queue tal-state))
