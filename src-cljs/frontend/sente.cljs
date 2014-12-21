(ns frontend.sente
  (:require-macros [cljs.core.async.macros :as asyncm :refer (go go-loop)])
  (:require [cljs.core.async :as async :refer (<! >! put! chan)]
            [clojure.set :as set]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [taoensso.sente  :as sente :refer (cb-success?)]
            [frontend.datascript :as ds]
            [datascript :as d]))

(defn send-msg [sente-state message & [timeout-ms callback-fn :as rest]]
  (if (-> sente-state :state deref :open?)
    (apply (:send-fn sente-state) message rest)
    (let [watch-id (utils/uuid)]
      (add-watch (:state sente-state) watch-id
                 (fn [key ref old new]
                   (when (:open? new)
                     (apply (:send-fn sente-state) message rest)
                     (remove-watch ref watch-id)))))))

(defn subscribe-to-document [sente-state document-id]
  (send-msg sente-state [:frontend/subscribe {:document-id document-id}] 10000
            (fn [data]
              (put! (:ch-recv sente-state) [:chsk/recv [:frontend/subscribe data]]))))

(defn fetch-subscribers [sente-state document-id]
  (send-msg sente-state [:frontend/fetch-subscribers {:document-id document-id}] 10000
            (fn [data]
              (put! (:ch-recv sente-state) [:chsk/recv [:frontend/fetch-subscribers data]]))))

(defmulti handle-message (fn [app-state message data]
                           (utils/mlog "handle-message" message data)
                           message))

(defmethod handle-message :default [app-state message data]
  (println "ws message" (pr-str message) (pr-str data)))

(defmethod handle-message :datomic/transaction [app-state message data]
  (let [datoms (:tx-data data)]
    (d/transact! (:db @app-state)
                 (map ds/datom->transaction datoms)
                 {:server-update true})))

(defmethod handle-message :frontend/subscriber-joined [app-state message data]
  (swap! app-state update-in [:subscribers (:client-uuid data)] merge (dissoc data :client-uuid)))

(defmethod handle-message :frontend/subscriber-left [app-state message data]
  (swap! app-state update-in [:subscribers] dissoc (:client-uuid data)))

;; TODO: update-when-in
(defmethod handle-message :frontend/mouse-move [app-state message data]
  (swap! app-state update-in [:subscribers (:client-uuid data)] merge (select-keys data [:mouse-position :tool :layers])))

(defmethod handle-message :frontend/share-mouse [app-state message data]
  (swap! app-state assoc-in [:subscribers (:mouse-owner-uuid data) :show-mouse?] (:show-mouse? data)))

(defmethod handle-message :frontend/update-subscriber [app-state message data]
  (swap! app-state update-in [:subscribers (:client-uuid data)] merge (:subscriber-data data)))

(defmethod handle-message :frontend/invite-response [app-state message data]
  (let [doc-id (:document/id data)
        response (:response data)]
    (swap! app-state update-in (state/invite-responses-path doc-id) conj response)))

;; These are a little bit different, we're putting the message on the channel in a send-msg callback
(defmethod handle-message :frontend/subscribe [app-state message data]
  (let [{:keys [document layers chats client-uuid]} data]
    ;; TODO: if this is a good idea, then make it the default
    (put! (get-in @app-state [:comms :controls])
          [:show-mouse-toggled {:client-uuid client-uuid
                                :show-mouse? true}])
    (d/transact! (:db @app-state)
                 (concat layers chats)
                 {:server-update true})))

(defmethod handle-message :frontend/fetch-subscribers [app-state message {:keys [subscribers]}]
  (swap! app-state update-in [:subscribers] (fn [s]
                                              (merge-with merge
                                                          subscribers
                                                          s))))


(defn do-something [app-state sente-state]
  (let [tap (async/chan (async/sliding-buffer 10))
        mult (:ch-recv-mult sente-state)]
    (async/tap mult tap)
    (go-loop []
      (when-let [{[type data] :event} (<! tap)]
        ;; other type is :chsk/state, sent when the ws is opened.
        ;; might be a good thing to watch when we reconnect?
        (when (= :chsk/recv type)
          (utils/swallow-errors
           (let [[message message-data] data]
             (handle-message app-state message message-data))))
        (recur)))))

(defn init [app-state]
  (let [{:keys [chsk ch-recv send-fn state] :as sente-state} (sente/make-channel-socket! "/chsk" {:type :auto})]
    (swap! app-state assoc :sente (assoc sente-state :ch-recv-mult (async/mult ch-recv)))
    (do-something app-state (:sente @app-state))))
