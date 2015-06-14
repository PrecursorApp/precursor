(ns frontend.talaria
  (:require [cljs.core.async :as async]
            [cljs-http.client :as http]
            [clojure.string :as str]
            [cognitect.transit :as transit]
            [goog.Uri.QueryData]
            [goog.Uri :as uri]
            [goog.events :as gevents]
            [goog.net.WebSocket :as ws]
            ;; remove for release
            [frontend.utils :as utils])
  (:import [goog.net.WebSocket.EventType])
  (:require-macros [cljs.core.async.macros :refer (go go-loop)]))

(defn decode-msg [msg]
  (let [r (transit/reader :json)]
    (transit/read r msg)))

(defn encode-msg [msg]
  (let [r (transit/writer :json)]
    (transit/write r msg)))

(defrecord AjaxSocket [recv-url send-url csrf-token on-open on-close on-error on-message on-reconnect]
  Object
  (send [ch msg]
    (if (.-open ch)
      (go
        (let [res (async/<! (http/post send-url {:body msg
                                                 :headers {"X-CSRF-Token" csrf-token
                                                           "Content-Type" "text/plain"}}))]
          (when-not (= 200 (:status res))
            (when (fn? on-error)
              (on-error res))
            (when (= "channel-closed" (:body res))
              (.close ch)))))
      (throw "Channel is closed")))
  (open [ch]
    (go
      (when (= "connected"
               (:body (async/<! (http/get recv-url {:query-params {:open? true}
                                                    :headers {"Content-Type" "text/plain"}}))))
        (set! (.-open ch) true)
        (go-loop []
          (let [resp (async/<! (http/get recv-url {:headers {"Content-Type" "text/plain"}
                                                   :timeout (* 1000 30)}))]
            (cond
              (keyword-identical? :timeout (:error-code resp)) nil

              (or (str/blank? (:body resp))
                  (not (:success resp)))
              (.close ch)

              (= "replace-existing" (:body resp)) nil

              :else (on-message (clj->js {:data (:body resp)})))
            (when-not (.-closed ch)
              (recur))))
        (when (fn? on-open)
          (on-open)))))
  (close [ch data]
    (when-not (.-closed ch)
      (set! (.-closed ch) true)
      (when (fn? on-close)
        (on-close data)))))

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

(defn pop-all [queue-atom]
  (loop [val @queue-atom]
    (if (compare-and-set! queue-atom val (empty val))
      val
      (recur @queue-atom))))

(defn send-msg [tal-state ws msg]
  (.send ws (encode-msg msg))
  (swap! tal-state assoc :last-send-time (js/Date.)))

(defn consume-send-queue [tal-state timer-atom]
  (let [send-queue (:send-queue @tal-state)]
    (when-let [ws (:ws @tal-state)]
      (doseq [timer-id (pop-all timer-atom)]
        (js/clearTimeout timer-id))
      (let [messages (pop-all send-queue)]
        (when (seq messages)
          (send-msg tal-state ws messages))))))

(defn start-send-queue [tal-state delay-ms]
  (let [send-queue (:send-queue @tal-state)
        timer-atom (atom #{})]
    (add-watch send-queue ::send-watcher (fn [_ _ old new]
                                           (when (> (count new) (count old))
                                             (let [timer-id (js/setTimeout #(consume-send-queue tal-state timer-atom)
                                                                           delay-ms)]
                                               (swap! timer-atom conj timer-id)))))
    (consume-send-queue tal-state timer-atom)))

(defn shutdown-send-queue [tal-state]
  (remove-watch (:send-queue @tal-state) ::send-watcher))

(defn close-connection [tal-state]
  (.close (:ws @tal-state)))

(defn consume-recv-queue [tal-state handler]
  (let [recv-queue (:recv-queue @tal-state)]
    (doseq [msg (pop-all recv-queue)]
      (cond
        (keyword-identical? :tal/reply (:op msg))
        (run-callback tal-state (:tal/cb-uuid msg) (:data msg))

        (keyword-identical? :tal/close (:op msg))
        (close-connection tal-state)

        :else
        (handler msg)))))

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

(defn make-url [{:keys [port path host secure? ws? params csrf-token]}]
  (let [scheme (if ws?
                 (if secure? "wss" "ws")
                 (if secure? "https" "http"))]
    (str (doto (goog.Uri.)
           (.setScheme scheme)
           (.setDomain host)
           (.setPort port)
           (.setPath path)
           (.setQueryData  (-> params
                             (assoc :csrf-token csrf-token)
                             clj->js
                             (goog.Uri.QueryData/createFromMap)))))))

(defn setup-ajax [url-parts tal-state & {:keys [on-open on-close on-error on-reconnect reconnecting?]
                                         :as args}]
  (let [url (make-url (assoc url-parts :path "/talaria" :ws? false))
        w (AjaxSocket. (make-url (assoc url-parts :path "/talaria/ajax-poll"))
                       (make-url (assoc url-parts :path "/talaria/ajax-send"))
                       (:csrf-token url-parts)
                       #(do
                          (utils/mlog "opened" %)
                          (let [timer-id (start-ping tal-state)]
                            (swap! tal-state (fn [s]
                                               (-> s
                                                 (assoc :open? true
                                                        :keep-alive-timer timer-id)
                                                 (dissoc :closed? :close-code :close-reason)))))
                          (start-send-queue tal-state 30)
                          (when (and reconnecting? (fn? on-reconnect))
                            (on-reconnect tal-state))

                          (when (fn? on-open)
                            (on-open tal-state)))
                       #(do
                          (utils/mlog "closed" %)
                          (shutdown-send-queue tal-state)
                          (swap! tal-state assoc
                                 :ws nil
                                 :open? false
                                 :closed? true
                                 :close-code (:status %)
                                 :close-reason (:body %))
                          (js/clearInterval (:keep-alive-timer @tal-state))
                          (when (fn? on-close)
                            (on-close tal-state %))
                          (js/setTimeout (fn []
                                           (when-not (:ws @tal-state)
                                             (utils/apply-map setup-ajax url-parts tal-state (assoc args :reconnecting? true))))
                                         1000))
                       #(do (utils/mlog "error" %)
                            (swap! tal-state assoc :last-error-time (js/Date.))
                            (when (fn? on-error)
                              (on-error tal-state)))
                       #(do
                          ;;(utils/mlog "message" %)
                          (swap! tal-state assoc :last-recv-time (js/Date.))
                          (swap! (:recv-queue @tal-state) (fn [q] (apply conj q (decode-msg (.-data %))))))
                       on-reconnect)]
    (swap! tal-state assoc :ws w)
    (start-send-queue tal-state 100)
    (.open w)))

(defn setup-ws [url-parts tal-state & {:keys [on-open on-close on-error on-reconnect reconnecting?]
                                       :as args}]
  (let [url (make-url (assoc url-parts :path "/talaria" :ws? true))
        w (js/WebSocket. url)]
    (swap! tal-state assoc :ws w)
    (aset w "onopen"
          #(do
             (utils/mlog "opened" %)
             (let [timer-id (start-ping tal-state)]
               (swap! tal-state (fn [s]
                                  (-> s
                                    (assoc :open? true
                                           :keep-alive-timer timer-id)
                                    (dissoc :closed? :close-code :close-reason)))))
             (start-send-queue tal-state 30)
             (when (and reconnecting? (fn? on-reconnect))
               (on-reconnect tal-state))

             (when (fn? on-open)
               (on-open tal-state))))
    (aset w "onclose"
          #(let [code (.-code %)
                 reason (.-reason %)]
             (utils/mlog "closed" %)
             (shutdown-send-queue tal-state)
             (swap! tal-state assoc
                    :ws nil
                    :open? false
                    :closed? true
                    :close-code code
                    :close-reason reason)
             (js/clearInterval (:keep-alive-timer @tal-state))
             (when (fn? on-close)
               (on-close tal-state {:code code
                                    :reason reason}))
             (if (= 1006 code)
               ;; ws failed, lets try ajax
               (utils/apply-map setup-ajax url-parts tal-state args)
               (js/setTimeout (fn []
                                (when-not (:open? @tal-state)
                                  (utils/apply-map setup-ws url-parts tal-state (assoc args :reconnecting? true))))
                              1000))))
    (aset w "onerror"
          #(do (utils/mlog "error" %)
               (swap! tal-state assoc :last-error-time (js/Date.))
               (when (fn? on-error)
                 (on-error tal-state))))
    (aset w "onmessage"
          #(do
             ;;(utils/mlog "message" %)
             (swap! tal-state assoc :last-recv-time (js/Date.))
             (swap! (:recv-queue @tal-state) (fn [q] (apply conj q (decode-msg (.-data %)))))))))

(defn init
  "Guaranteed to run keep-alive every keep-alive-ms interval, but may probably run
   about twice as often"
  [url-parts & {:keys [on-open on-close on-error on-reconnect keep-alive-ms test-ajax]
                :or {keep-alive-ms 60000}}]

  (let [send-queue (atom [])
        recv-queue (atom [])
        tal-state (atom {:keep-alive-ms keep-alive-ms
                         :open? false
                         :send-queue send-queue
                         :recv-queue recv-queue
                         :callbacks {}})]
    (if (and js/window.WebSocket
             (not test-ajax))
      (setup-ws url-parts tal-state :on-open on-open :on-error on-error :on-reconnect on-reconnect)
      (setup-ajax url-parts tal-state :on-open on-open :on-error on-error :on-reconnect on-reconnect))

    tal-state))

(defn shutdown [tal-state]
  (close-connection tal-state)
  (shutdown-send-queue tal-state))
