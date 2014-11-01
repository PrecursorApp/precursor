(ns frontend.sente
  (:require-macros [cljs.core.async.macros :as asyncm :refer (go go-loop)])
  (:require [cljs.core.async :as async :refer (<! >! put! chan)]
            [clojure.set :as set]
            [frontend.utils :as utils :include-macros true]
            [taoensso.sente  :as sente :refer (cb-success?)]
            [frontend.datascript :as ds]
            [datascript :as d]))

(defn send-msg [sente-state message & [timeout-ms callback-fn :as rest]]
  (if (-> sente-state :state deref :open?)
    (apply (:send-fn sente-state) message rest)
    ;; wait for connection (this probably works)
    (let [tap (async/chan (async/sliding-buffer 1))]
      (async/tap (:ch-recv-mult sente-state) tap)
      (async/take! tap
                   (fn [val]
                     (apply (:send-fn sente-state) message rest))))))

(defn subscribe-to-document [sente-state app-state document-id]
  (send-msg sente-state [:frontend/subscribe {:document-id document-id}] 2000
            (fn [{:keys [document layers client-uuid]}]
              (swap! app-state assoc :client-uuid client-uuid)
              (d/transact (:db @app-state)
                          ;; hack to prevent loops
                          (conj layers {:db/id -1 :server/update true})))))

(defn fetch-subscribers [sente-state app-state document-id]
  (send-msg sente-state [:frontend/fetch-subscribers {:document-id document-id}] 2000
            (fn [{:keys [subscribers]}]
              (swap! app-state update-in [:subscribers] (fn [s]
                                                          (merge-with merge
                                                                      subscribers
                                                                      s))))))

(defmulti handle-message (fn [app-state message data]
                           (utils/mlog "handle-message" message data)
                           message))

(defmethod handle-message :default [app-state message data]
  (println "ws message" (pr-str message) (pr-str data)))

(defmethod handle-message :datomic/transaction [app-state message data]
  (let [datoms (:tx-data data)]
    (d/transact (:db @app-state)
                (map ds/datom->transaction datoms))))


(defmethod handle-message :frontend/subscriber-joined [app-state message data]
  (swap! app-state update-in [:subscribers] (fn [s] (merge-with merge
                                                                {(:client-uuid data) {}}
                                                                s))))

(defmethod handle-message :frontend/subscriber-left [app-state message data]
  (swap! app-state update-in [:subscribers] dissoc (:client-uuid data)))

(defmethod handle-message :frontend/mouse-move [app-state message data]
  (swap! app-state update-in [:subscribers (:client-uuid data)] merge (select-keys data [:mouse-position :tool :layer])))

(defmethod handle-message :frontend/share-mouse [app-state message data]
  (swap! app-state assoc-in [:subscribers (:mouse-owner-uuid data) :show-mouse?] (:show-mouse? data)))

(defn do-something [app-state sente-state]
  (let [tap (async/chan (async/sliding-buffer 10))
        mult (:ch-recv-mult sente-state)]
    (async/tap mult tap)
    (go-loop []
             (when-let [res (<! tap)]
               (utils/swallow-errors (handle-message app-state (first (second (:event res))) (second (second (:event res)))))
               (recur)))))

(defn init [app-state]
  (let [{:keys [chsk ch-recv send-fn state] :as sente-state} (sente/make-channel-socket! "/chsk" {:type :auto})]
    (swap! app-state assoc :sente (assoc sente-state :ch-recv-mult (async/mult ch-recv)))
    (subscribe-to-document (:sente @app-state) app-state (-> @app-state :document/id))
    (fetch-subscribers (:sente @app-state) app-state (-> @app-state :document/id))
    (do-something app-state (:sente @app-state))))
