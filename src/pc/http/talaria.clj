(ns pc.http.talaria
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [cognitect.transit :as transit]
            [immutant.web.async :as immutant]
            ;; have to extract the utils fns we use
            [pc.utils :as utils])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(defonce talaria-state (ref {:connections {}
                             :stats {}}))

(defn init []
  (dosync (ref-set talaria-state (let [ch (async/chan (async/sliding-buffer 1024))]
                                   {:connections {}
                                    :msg-ch ch
                                    :msg-mult (async/mult ch)
                                    :stats {}})))
  talaria-state)

(defn tap-msg-ch [talaria-state]
  (let [tap-ch (async/chan (async/sliding-buffer 1024))
        mult-ch (:msg-mult @talaria-state)]
    (async/tap mult-ch tap-ch)
    tap-ch))

(defn get-ch [tal-state ch-id]
  (get-in @tal-state [:connections ch-id :channel]))

(defn ch-id [ch]
  (:talaria/channel-id (immutant/originating-request ch)))

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
                                                       :type :ws})
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
    (add-channel tal-state (ch-id ch) ch)))

(defn handle-ws-error [tal-state]
  (fn [ch throwable]
    (let [id (ch-id ch)]
      (log/errorf throwable "error for channel with id %s" id)
      (record-error tal-state id throwable))))

(defn handle-ws-close [tal-state]
  (fn [ch {:keys [code reason] :as args}]
    (let [id (ch-id ch)]
      (log/infof "channel with id %s closed %s" id args)
      (remove-channel tal-state id))))

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
          msg-ch (:msg-ch @tal-state)]
      (record-msg tal-state id msg)
      (async/put! msg-ch (decode-msg msg)))))

(defn send! [tal-state ch-id msg & {:keys [on-success on-error]}]
  (when-let [ch (get-ch tal-state ch-id)]
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


;; debug methods
(defn send-all [tal-state msg]
  (doseq [[id _] (:connections @talaria-state)]
    (send! tal-state id msg)))

(defn all-channels [talaria-state]
  (map (comp :channel second) (:connections @talaria-state)))
