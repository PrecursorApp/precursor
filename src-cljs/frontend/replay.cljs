(ns frontend.replay
  (:require [cljs.core.async :as async :refer (<! >! put! chan)]
            [clojure.set :as set]
            [datascript :as d]
            [frontend.datascript :as ds]
            [frontend.sente :as sente]
            [frontend.state :as state]
            [frontend.utils :as utils])
  (:require-macros [cljs.core.async.macros :as asyncm :refer (go go-loop)]))

(defn replay [state & {:keys [delay-ms sleep-ms]
                       :or {delay-ms 0
                            sleep-ms 150}}]
  (let [replay-ch (chan)
        doc-id (:document/id state)]
    ;; XXX: error handling
    ;; XXX: way to interrupt the loop
    (go
      (try
        (<! (async/timeout delay-ms))
        (when-let [tx-ids (seq (:tx-ids (<! (sente/ch-send-msg (:sente state)
                                                               [:document/transaction-ids {:document/id doc-id}]
                                                               10000
                                                               replay-ch))))]
          (loop [tx-ids tx-ids]
            (let [tx (:document/transaction (<! (sente/ch-send-msg (:sente state)
                                                                   [:document/fetch-transaction {:document/id doc-id
                                                                                                 :tx-id (first tx-ids)}]
                                                                   10000
                                                                   replay-ch)))]
              (d/transact! (:db state)
                           (map ds/datom->transaction (:tx-data tx))
                           {:server-update true})
              (when (next tx-ids)
                (when (seq tx)
                  (<! (async/timeout sleep-ms)))
                (recur (next tx-ids))))))
        (finally
          (async/close! replay-ch))))))

(defn replay-and-subscribe [state & {:keys [delay-ms sleep-ms]
                                     :or {delay-ms 0
                                          sleep-ms 150}}]
  (go
    (<! (replay state :delay-ms delay-ms :sleep-ms sleep-ms))
    (sente/subscribe-to-document (:sente state) (:comms state) (:document/id state))))
