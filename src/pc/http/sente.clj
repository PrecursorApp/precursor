(ns pc.http.sente
  (:require [clojure.core.async :as async]
            [clojure.set :as set]
            [clojure.tools.logging :as log]
            [taoensso.sente :as sente])
  (:import java.util.UUID))

;; TODO: find a way to restart sente
(defonce sente-state (atom {}))

(defn user-id-fn [ring-req]
  (UUID/randomUUID))

;; hash-map of document-id to set of connected user-ids
;; Used to keep track of which transactions to send to which user
;; sente's channel handling stuff is not much fun to work with :(
(defonce document-subs (atom {}))

;; XXX: fix this once we annotate transactions with document ids
(defn notify-transaction [transaction]
  (doseq [uid (apply set/union (vals @document-subs))]
    ((:send-fn @sente-state) uid [:datomic/transaction (select-keys transaction [:tx-data])])))

(defn ws-handler-dispatch-fn [req]
  (-> req :event first))

(defmulti ws-handler ws-handler-dispatch-fn)

(defmethod ws-handler :default [req]
  (def req req)
  (log/infof "%s for %s" (:event req) (:client-uuid req)))

(defn clean-document-subs [uuid]
  (swap! document-subs (fn [ds]
                         ;; Could be optimized...
                         (reduce (fn [acc [document-id user-ids]]
                                   (if-not (contains? user-ids uuid)
                                     acc
                                     (let [new-user-ids (disj user-ids uuid)]
                                       (if (empty? new-user-ids)
                                         (dissoc acc document-id)
                                         (assoc acc document-id new-user-ids)))))
                                 ds ds))))

(defmethod ws-handler :chsk/uidport-close [{:keys [client-uuid] :as req}]
  (log/infof "closing connection for %s" client-uuid)
  (clean-document-subs client-uuid))

(defn subscribe-to-doc [document-id uuid]
  (swap! document-subs update-in [document-id] (fnil conj #{}) uuid))

(defmethod ws-handler :frontend/subscribe [{:keys [client-uuid ?data] :as req}]
  (let [document-id (-> ?data :document-id)]
    (log/infof "subscribing %s to %s" client-uuid document-id)
    (subscribe-to-doc document-id client-uuid)))

(defn setup-ws-handlers [sente-state]
  (let [tap (async/chan (async/sliding-buffer 100))
        mult (async/mult (:ch-recv sente-state))]
    (async/tap mult tap)
    (async/go-loop []
                   (when-let [req (async/<! tap)]
                     (try
                       (ws-handler req)
                       (catch Exception e
                         (log/error e)))
                     (recur)))))

(defn init []
  (let [{:keys [ch-recv send-fn ajax-post-fn connected-uids
                ajax-get-or-ws-handshake-fn] :as fns} (sente/make-channel-socket! {:user-id-fn #'user-id-fn})]
    (reset! sente-state fns)
    (setup-ws-handlers fns)
    fns))
