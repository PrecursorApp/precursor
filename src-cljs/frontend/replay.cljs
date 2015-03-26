(ns frontend.replay
  (:require [cljs.core.async :as async :refer (<! >! put! chan)]
            [clojure.set :as set]
            [datascript :as d]
            [frontend.datascript :as ds]
            [frontend.sente :as sente]
            [frontend.state :as state]
            [frontend.utils :as utils])
  (:require-macros [cljs.core.async.macros :as asyncm :refer (go go-loop)]))

(defn replay [state & {:keys [delay-ms sleep-ms tx-count interrupt-ch]
                       :or {delay-ms 0
                            sleep-ms 150}}]
  (let [replay-ch (chan)
        doc-id (:document/id state)
        interrupted (atom false)
        api-ch (get-in state [:comms :api])]
    (put! api-ch [:progress :success {:active true :percent 0 :expected-tick-duration (+ sleep-ms 180)}])
    (when interrupt-ch
      (async/take! interrupt-ch #(when % (reset! interrupted true))))
    (utils/go+
     (try
       (<! (async/timeout delay-ms))
       (when-let [tx-ids (-> (sente/ch-send-msg (:sente state)
                                                [:document/transaction-ids {:document/id doc-id}]
                                                10000
                                                replay-ch)
                           (<!)
                           :tx-ids
                           seq)]
         (let [tx-count (or tx-count (count tx-ids))]
           (loop [tx-ids (take tx-count tx-ids)
                  i 0
                  start (js/Date.)
                  durations []]
             (let [tx (-> (sente/ch-send-msg (:sente state)
                                             [:document/fetch-transaction {:document/id doc-id
                                                                           :tx-id (first tx-ids)}]
                                             10000
                                             replay-ch)
                        (<!)
                        (:document/transaction))]
               (if @interrupted
                 ::interrupted
                 (do
                   (d/transact! (:db state)
                                (map ds/datom->transaction (:tx-data tx))
                                {:server-update true})
                   (put! api-ch [:progress :success {:active true
                                                     :percent (* 100 (/ (inc i)
                                                                        tx-count))
                                                     :expected-tick-duration (apply max sleep-ms (- (.getTime (js/Date.))
                                                                                                    (.getTime start))
                                                                                    (take-last 5 durations))}])
                   (when (next tx-ids)
                     (when (seq tx)
                       (<! (async/timeout (- sleep-ms
                                             (- (.getTime (js/Date.))
                                                (.getTime start))))))
                     (recur (next tx-ids)
                            (inc i)
                            (js/Date.)
                            (conj durations (- (.getTime (js/Date.))
                                               (.getTime start)))))))))))
       (finally
         (put! api-ch [:progress :success {:active false}])
         (async/close! replay-ch)
         (when interrupt-ch
           (async/close! interrupt-ch)))))))

(defn replay-and-subscribe [state & {:keys [delay-ms sleep-ms tx-count] :as args}]
  (utils/go+
    (when-not (keyword-identical? ::interrupted (<! (utils/apply-map replay state args)))
      (sente/subscribe-to-document (:sente state) (:comms state) (:document/id state)))))
