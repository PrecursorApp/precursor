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
        (recur (pop-atom send-queue))))))

(defn start-send-queue [tal-state]
  (let [send-queue (:send-queue @tal-state)]
    (add-watch send-queue ::send-watcher (fn [_ _ old new]
                                           (when (> (count new) (count old))
                                             (consume-send-queue tal-state))))
    (consume-send-queue tal-state)))

(defn shutdown-send-queue [tal-state]
  (remove-watch (:send-queue @tal-state) ::send-watcher))


;; XXX: handle callbacks and timeouts
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

(defonce debug-ws (atom nil))

(defn init [url & {:keys [on-open on-close on-error]}]
  (let [ ;; creates class that will handle auto-reconnecting on failure
        w (goog.net.WebSocket.)
        _ (reset! debug-ws w)
        ch (async/chan (async/sliding-buffer 1024))
        send-queue (atom [])
        recv-queue (atom [])
        tal-state (atom {:ws w
                         :open? false
                         :send-queue send-queue
                         :recv-queue recv-queue
                         :callbacks {}})]
    (gevents/listen w goog.net.WebSocket.EventType.OPENED
                    #(do
                       (utils/mlog "opened" %)
                       (swap! tal-state (fn [s]
                                          (-> s
                                            (assoc :open? true)
                                            (dissoc :closed? :close-code :close-reason))))
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
                       (swap! tal-state assoc :last-message-time (js/Date.))
                       (swap! recv-queue conj (decode-msg (.-message %)))))
    (.open w url)
    tal-state))

(defn shutdown [tal-state]
  (.close (:ws @tal-state))
  (shutdown-send-queue tal-state))

;; (defonce testing (init "ws://localhost:8080/talaria"))

;; (defn test-with-atom-queues [count]
;;   (queue-msg testing [:start-timer {}])
;;   (dotimes [x count]
;;     (queue-msg testing [:frontend/test {:a 1 :b x}]))
;;   (queue-msg testing [:stop-timer {:type :atom}]))

;; (defn test-straight-sends [count]
;;   (let [ws (:ws @testing)]
;;     (.send ws (encode-msg {:op :start-timer :data {}}))
;;     (dotimes [x count]
;;       (.send ws (encode-msg {:op :frontend/test :data {:a 1 :b x}})))
;;     (.send ws (encode-msg {:op :stop-timer :data {:type :nothing}}))))

;; (defn test-core-async [count]
;;   (let [messages (concat [{:op :start-timer :data {}}]
;;                          (for [x (range count)]
;;                            {:op :frontend/test :data {:a 1 :b x}})
;;                          [{:op :stop-timer :data {:type :core-async}}])
;;         ch (async/chan (async/sliding-buffer (+ 1024 count)))
;;         ws (:ws @testing)]
;;     (go-loop []
;;       (when-let [msg (async/<! ch)]
;;         (.send ws (encode-msg msg))
;;         (if (= :stop-timer (:op msg))
;;           (async/close! ch)
;;           (recur))))
;;     (doseq [msg messages]
;;       (async/put! ch msg))))
