(ns pc.http.talaria
  (:require [clj-time.core :as time]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [cognitect.transit :as transit]
            [immutant.web.async :as immutant]
            [pc.delay :as delay]
            ;; have to extract the utils fns we use
            [pc.utils :as utils])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(defonce talaria-state (ref {:connections {}
                             :stats {}}))

(defn init [& {:keys [ws-delay ajax-delay]
               :or {ws-delay 30
                    ajax-delay 100}}]
  (let [ch (async/chan (async/sliding-buffer 1024))
        async-pool (delay/make-pool!)]
    (dosync (ref-set talaria-state {:connections {}
                                    :msg-ch ch
                                    :msg-mult (async/mult ch)
                                    :async-pool async-pool
                                    :ws-delay ws-delay
                                    :ajax-delay ajax-delay
                                    :stats {}})))
  talaria-state)

(defn shutdown [tal-state]
  (let [s @tal-state]
    (async/close! (:msg-ch s))
    (delay/shutdown-pool! (:async-pool s))))

(defn tap-msg-ch [talaria-state]
  (let [tap-ch (async/chan (async/sliding-buffer 1024))
        mult-ch (:msg-mult @talaria-state)]
    (async/tap mult-ch tap-ch)
    tap-ch))

(defn get-channel-info [tal-state ch-id]
  (get-in @tal-state [:connections ch-id]))

(defn ch-id [ch]
  (:tal/ch-id (immutant/originating-request ch)))

(defn add-channel
  "Adds channel to state, given an id. Will throw if a channel
   already exists for the given id. Updates global stats."
  [tal-state id ch]
  (dosync
   (commute tal-state (fn [s]
                        ;; XXX: should this also close the channel?
                        ;;      Need to make sure that we don't have un-used channels lying around.
                        ;;      Maybe this should result in all channels for that id being closed?
                        (assert (empty? (get-in s [:connections id])))
                        (-> s
                          (assoc-in [:connections id] {:channel ch
                                                       :type :ws
                                                       ;; store delay here so that we can optimize based on
                                                       ;; latency
                                                       :send-delay (:ws-delay s)
                                                       :send-queue (atom [])})
                          (update-in [:stats :connection-count] (fnil inc 0)))))))

(defn record-error
  "Records error for a given channel and updates global stats"
  [tal-state id error]
  (dosync
   (commute tal-state (fn [s]
                        (-> s
                          (utils/update-when-in [:connections id] (fn [info] (-> info
                                                                               (assoc :last-error error)
                                                                               (update-in [:error-count] (fnil inc 0)))))
                          (assoc-in [:stats :last-error] error)
                          (update-in [:stats :error-count] (fnil inc 0)))))))

(defn remove-channel
  "Removes channel and updates global stats"
  [tal-state id]
  (dosync
   (commute tal-state (fn [s]
                        (-> s
                          (update-in [:connections] dissoc id)
                          (update-in [:stats :connection-count] (fnil dec 0)))))))

(defn record-msg
  "Updates global stats for messages"
  [tal-state id msg]
  (dosync
   (commute tal-state (fn [s]
                        (-> s
                          (utils/update-when-in [:connections id] (fn [info] (-> info
                                                                               (update-in [:receive-count] (fnil inc 0)))))
                          (update-in [:stats :receive-count] (fnil inc 0)))))))

(defn record-send
  "Updates global stats for messages"
  [tal-state id msg]
  (dosync
   (commute tal-state (fn [s]
                        (-> s
                          (utils/update-when-in [:connections id] (fn [info] (-> info
                                                                               (update-in [:send-count] (fnil inc 0))
                                                                               (update-in [:in-flight] (fnil inc 0)))))
                          (update-in [:stats :send-count] (fnil inc 0))
                          (update-in [:stats :in-flight] (fnil inc 0)))))))

(defn record-send-success
  "Updates global stats for messages"
  [tal-state id msg]
  (dosync
   (commute tal-state (fn [s]
                        (-> s
                          (utils/update-when-in [:connections id] (fn [info] (-> info
                                                                               (update-in [:send-success-count] (fnil inc 0))
                                                                               (update-in [:in-flight] (fnil dec 0)))))
                          (update-in [:stats :send-success-count] (fnil inc 0))
                          (update-in [:stats :in-flight] (fnil dec 0)))))))

(defn record-send-error
  "Updates global stats for messages"
  [tal-state id msg error]
  (dosync
   (commute tal-state (fn [s]
                        (-> s
                          (utils/update-when-in [:connections id] (fn [info] (-> info
                                                                               (assoc :last-send-error error)
                                                                               (update-in [:send-error-count] (fnil inc 0))
                                                                               (update-in [:in-flight] (fnil dec 0)))))
                          (assoc-in [:stats :last-send-error] error)
                          (update-in [:stats :send-error-count] (fnil inc 0))
                          (update-in [:stats :in-flight] (fnil dec 0)))))))

(defn handle-ws-open [tal-state]
  (fn [ch]
    (let [id (ch-id ch)
          msg-ch (:msg-ch @tal-state)]
      (add-channel tal-state id ch)
      (async/put! msg-ch {:op :tal/channel-open
                          :tal/ch ch
                          :tal/ch-id id
                          ;; for final release, this should be obtained by
                          ;; passing msg into a fn
                          :tal/ring-req (immutant/originating-request ch)
                          :tal/state tal-state}))))

(defn handle-ws-error [tal-state]
  (fn [ch throwable]
    (let [id (ch-id ch)]
      (log/errorf throwable "error for channel with id %s" id)
      (record-error tal-state id throwable))))

(defn handle-ws-close [tal-state]
  (fn [ch {:keys [code reason] :as args}]
    (let [id (ch-id ch)
          msg-ch (:msg-ch @tal-state)]
      (log/infof "channel with id %s closed %s" id args)
      (remove-channel tal-state id)
      (async/put! msg-ch {:op :tal/channel-close
                          :data args
                          :tal/ch ch
                          :tal/ch-id id
                          ;; for final release, this should be obtained by
                          ;; passing msg into a fn
                          :tal/ring-req (immutant/originating-request ch)
                          :tal/state tal-state}))))

(defn decode-msg [msg]
  (-> msg
    (.getBytes "UTF-8")
    (io/input-stream)
    (transit/reader :json)
    (transit/read)))

(defn encode-msg [msg]
  (let [out (ByteArrayOutputStream. 4096)
        w (transit/writer out :json)]
    (transit/write w msg)
    (.toString out)))

(defn handle-ws-msg [tal-state]
  (fn [ch msg]
    (let [id (ch-id ch)
          msg-ch (:msg-ch @tal-state)
          decoded-msg (decode-msg msg)]
      (record-msg tal-state id msg)
      (async/put! msg-ch (assoc decoded-msg
                                :tal/ch ch
                                :tal/ch-id id
                                ;; for final release, this should be obtained by
                                ;; passing msg into a fn
                                :tal/ring-req (immutant/originating-request ch)
                                :tal/state tal-state)))))

(defn send! [tal-state ch-id msg & {:keys [on-success on-error]}]
  (when-let [ch (:channel (get-channel-info tal-state ch-id))]
    (assert (vector? msg))
    (let [res (immutant/send! ch
                              (encode-msg msg)
                              {:close? false
                               :on-success (fn []
                                             (record-send-success tal-state ch-id msg)
                                             (when (fn? on-success)
                                               (on-success)))
                               :on-error (fn [throwable]
                                           (log/errorf throwable "error sending message for channel with id %s" ch-id)
                                           (record-send-error tal-state ch-id msg throwable)
                                           (when (fn? on-error)
                                             (on-error throwable)))})]
      (when res
        (record-send tal-state ch-id msg))
      res)))

(defn pop-all [queue-atom]
  (loop [val @queue-atom]
    (if (compare-and-set! queue-atom val [])
      val
      (recur @queue-atom))))

(defn combine-callbacks [callbacks]
  (reduce (fn [acc cb]
            (if (fn? cb)
              (if (fn? acc)
                (juxt acc cb)
                cb)
              acc))
          callbacks))

(defn send-queued! [tal-state ch-id]
  (when-let [ch-info (get-channel-info tal-state ch-id)]
    (let [messages (pop-all (:send-queue ch-info))]
      (when (seq messages)
        (send! tal-state ch-id (mapv :msg messages)
               :on-success (combine-callbacks (map :on-success messages))
               :on-error (combine-callbacks (map :on-error messages)))))))

(defn schedule-send [tal-state ch-id delay-ms]
  (delay/delay-fn (:async-pool @tal-state)
                  delay-ms
                  #(send-queued! tal-state ch-id)))

(defn queue-msg! [tal-state ch-id msg & {:keys [on-success on-error]}]
  (when-let [channel-info (get-channel-info tal-state ch-id)]
    (swap! (:send-queue channel-info) conj {:msg msg
                                            :on-success on-success
                                            :on-error on-error})
    (schedule-send tal-state ch-id (:send-delay channel-info))))


;; debug methods
(defn send-all [tal-state msg]
  (doseq [[id _] (:connections @talaria-state)]
    (send! tal-state id msg)))

(defn all-channels [talaria-state]
  (map (comp :channel second) (:connections @talaria-state)))
