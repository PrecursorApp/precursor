(ns pc.http.sente.common
  (:require [pc.http.talaria :as tal]))

(defonce client-stats (atom {}))

(defn client-id->tal-uuid [client-id]
  (some->> client-id
    (get @client-stats)
    :tal/ch-id))

(defn send-msg [req client-id msg & [handlers]]
  (when-let [sente-state (:sente-state req)]
    ((:send-fn @sente-state) client-id msg handlers))
  (when-let [tal-state (:tal/state req)]
    (tal/queue-msg! tal-state (client-id->tal-uuid client-id) (if (vector? msg)
                                                                {:op (first msg)
                                                                 :data (second msg)}
                                                                msg)
                    :on-success (when (:on-complete handlers)
                                  (:on-complete handlers))
                    :on-error (when (:on-complete handlers)
                                (fn [throwable]
                                  ((:on-complete handlers)))))))
(defn send-reply [req data]
  (when-let [reply-fn (:?reply-fn req)]
    (reply-fn data))
  (when-let [tal-state (:tal/state req)]
    (tal/queue-msg! tal-state (:tal/ch-id req) {:op :tal/reply
                                                :data data
                                                :tal/cb-uuid (:tal/cb-uuid req)})))
