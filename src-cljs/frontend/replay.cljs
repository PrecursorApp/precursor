(ns frontend.replay
  (:require [cljs.core.async :as async :refer (<! >! put! chan)]
            [clojure.set :as set]
            [datascript :as d]
            [frontend.datascript :as ds]
            [frontend.db :as fdb]
            [frontend.sente :as sente]
            [frontend.state :as state]
            [frontend.utils :as utils]
            [taoensso.sente])
  (:require-macros [cljs.core.async.macros :as asyncm :refer (go go-loop)]))

(defn replay [state & {:keys [delay-ms sleep-ms tx-count interrupt-ch]
                       :or {delay-ms 0
                            sleep-ms 150}}]
  (let [replay-ch (chan)
        doc-id (:document/id state)
        interrupted (atom false)
        api-ch (get-in state [:comms :api])
        ;; hack to workaround finally squashing return value
        result (atom nil)]
    (put! api-ch [:progress :success {:active true :percent 0 :expected-tick-duration (+ sleep-ms 180)}])
    (when interrupt-ch
      (async/take! interrupt-ch #(when % (reset! interrupted true))))
    (utils/go+
     (try
       (<! (async/timeout delay-ms))
       (reset!
        result
        (when-let [tx-ids (-> (sente/ch-send-msg (:sente state)
                                                 [:document/transaction-ids {:document/id doc-id}]
                                                 2500
                                                 replay-ch)
                            (<!)
                            :tx-ids
                            seq)]
          (let [tx-count (or tx-count (count tx-ids))]
            (loop [tx-ids (take tx-count tx-ids)
                   i 0
                   start (js/Date.)
                   durations []]
              (let [resp (-> (sente/ch-send-msg (:sente state)
                                                [:document/fetch-transaction {:document/id doc-id
                                                                              :tx-id (first tx-ids)}]
                                                2500
                                                replay-ch)
                           (<!))
                    tx (:document/transaction resp)]
                (if-not (taoensso.sente/cb-success? resp)
                  ::error
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
                                                  (.getTime start)))))))))))))
       (catch js/Error e
         (utils/merror e)
         (reset! result ::error))
       (finally
         (put! api-ch [:progress :success {:active false}])
         (async/close! replay-ch)
         (when interrupt-ch
           (async/close! interrupt-ch))))
     ;; hack to prevent finally clause from swallowing our result
     @result)))

(defn replay-and-subscribe [state & {:keys [delay-ms sleep-ms tx-count] :as args}]
  (utils/go+
   (case (<! (utils/apply-map replay state args))
     ::interrupted nil
     ::error (do (fdb/reset-db! (:db state) nil)
                 (sente/subscribe-to-document (:sente state) (:comms state) (:document/id state)))
     (sente/subscribe-to-document (:sente state) (:comms state) (:document/id state)))))
