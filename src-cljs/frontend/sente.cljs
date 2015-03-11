(ns frontend.sente
  (:require [cemerick.url :as url]
            [cljs.core.async :as async :refer (<! >! put! chan)]
            [clojure.set :as set]
            [datascript :as d]
            [frontend.datascript :as ds]
            [frontend.datetime :as datetime]
            [frontend.models.chat :as chat-model]
            [frontend.state :as state]
            [frontend.stats :as stats]
            [frontend.subscribers :as subs]
            [frontend.utils :as utils :include-macros true]
            [goog.labs.dom.PageVisibilityMonitor]
            [taoensso.sente :as sente])
  (:require-macros [cljs.core.async.macros :as asyncm :refer (go go-loop)]))

(defn send-msg [sente-state message & [timeout-ms callback-fn :as rest]]
  (if (-> sente-state :state deref :open?)
    (apply (:send-fn sente-state) message rest)
    (let [watch-id (utils/uuid)]
      ;; TODO: handle this in the handle-message fn below
      (add-watch (:state sente-state) watch-id
                 (fn [key ref old new]
                   (when (:open? new)
                     (apply (:send-fn sente-state) message rest)
                     (remove-watch ref watch-id)))))))

(defn update-server-offset [sente-state]
  (let [start (goog/now)]
    (send-msg sente-state [:server/timestamp] 1000 (fn [reply]
                                                     (when (sente/cb-success? reply)
                                                       (let [latency (- (goog/now) start)]
                                                         (datetime/update-server-offset (:date (second reply)) latency)))))))

(defn subscribe-to-document [sente-state comms document-id & {:keys [requested-color requested-remainder]}]
  (send-msg sente-state [:frontend/subscribe {:document-id document-id
                                              :requested-color requested-color
                                              :requested-remainder requested-remainder}]
            5000
            (fn [reply]
              (if (sente/cb-success? reply)
                (put! (:api comms) [(first reply) :success (assoc (second reply)
                                                                  :context {:document-id document-id})])
                (put! (:errors comms) [:subscribe-to-document-error {:document-id document-id}])))))

(defn subscribe-to-team [sente-state team-uuid]
  (send-msg sente-state [:team/subscribe {:team/uuid team-uuid}]))

(defn fetch-subscribers [sente-state document-id]
  (send-msg sente-state [:frontend/fetch-subscribers {:document-id document-id}] 10000
            (fn [data]
              (put! (:ch-recv sente-state) [:chsk/recv [:frontend/fetch-subscribers data]]))))

(defmulti handle-message (fn [app-state message data]
                           (utils/mlog "handle-message" message data)
                           message))

(defmethod handle-message :default [app-state message data]
  (utils/mlog "ws message" (pr-str message) (pr-str data)))

(defmethod handle-message :datomic/transaction [app-state message data]
  (let [datoms (:tx-data data)]
    (d/transact! (:db @app-state)
                 (map ds/datom->transaction datoms)
                 {:server-update true})))

(defmethod handle-message :team/transaction [app-state message data]
  (let [datoms (:tx-data data)]
    (d/transact! (:team-db @app-state)
                 (map ds/datom->transaction datoms)
                 {:server-update true})))

(defmethod handle-message :frontend/subscriber-joined [app-state message data]
  (swap! app-state subs/add-subscriber-data (:client-id data) data))

(defmethod handle-message :frontend/subscriber-left [app-state message data]
  (swap! app-state subs/remove-subscriber (:client-id data)))

(defmethod handle-message :frontend/mouse-move [app-state message data]
  (swap! app-state subs/maybe-add-subscriber-data (:client-id data) data))

(defmethod handle-message :frontend/db-entities [app-state message data]
  (when (= (:document/id data) (:document/id @app-state))
    (d/transact! (:db @app-state)
                 (:entities data)
                 {:server-update true})))

(defmethod handle-message :team/db-entities [app-state message data]
  (when (= (:team/uuid data) (get-in @app-state [:team :team/uuid]))
    (d/transact! (:team-db @app-state)
                 (:entities data)
                 {:server-update true})))

(defmethod handle-message :frontend/custs [app-state message data]
  (swap! app-state update-in [:cust-data :uuid->cust] merge (:uuid->cust data)))

(defmethod handle-message :frontend/invite-response [app-state message data]
  (let [doc-id (:document/id data)
        response (:response data)]
    (swap! app-state update-in (state/invite-responses-path doc-id) conj response)))

(defmethod handle-message :frontend/subscribers [app-state message {:keys [subscribers] :as data}]
  (when (= (:document/id data) (:document/id @app-state))
    (swap! app-state #(reduce (fn [state [client-id subscriber-data]]
                                (subs/add-subscriber-data state client-id subscriber-data))
                              % subscribers))))

(defmethod handle-message :frontend/error [app-state message data]
  (put! (get-in @app-state [:comms :errors]) [:document-permission-error data])
  (utils/inspect data))

(defmethod handle-message :frontend/stats [app-state message data]
  (send-msg (:sente @app-state)
            [:frontend/stats
             {:stats (stats/gather-stats @app-state)}]))

(defmethod handle-message :frontend/refresh [app-state message data]
  (let [refresh-url (-> (url/url js/window.location)
                      (update-in [:query] merge {"x" (get-in @app-state [:camera :x])
                                                 "y" (get-in @app-state [:camera :y])
                                                 "z" (get-in @app-state [:camera :zf])})
                      str)]
    (if (or (.isHidden (goog.labs.dom.PageVisibilityMonitor.))
            (:force-refresh data))
      (set! js/window.location refresh-url)
      (chat-model/create-bot-chat (:db @app-state) @app-state [:span "We've just released some upgrades! Please "
                                                               [:a {:href refresh-url
                                                                    :target "_self"}
                                                                "click to refresh"]
                                                               " now to avoid losing any work."]))))

(defmethod handle-message :chsk/state [app-state message data]
  (let [state @app-state]
    (when (and (:open? data)
               (not (:first-open? data))
               (:document/id state))
      ;; TODO: This seems like a bad place for this. Can we share the same code that
      ;;       we use for subscribing from the nav channel in the first place?
      (subscribe-to-document
       (:sente state) (:comms state) (:document/id state)
       :requested-color (get-in state [:subscribers :info (:client-id state) :color])
       :requested-remainder (get-in state [:subscribers :info (:client-id state) :frontend-id-seed :remainder])))))


(defn do-something [app-state sente-state]
  (let [tap (async/chan (async/sliding-buffer 10))
        mult (:ch-recv-mult sente-state)]
    (async/tap mult tap)
    (go-loop []
      (when-let [{[type data] :event :as stuff} (<! tap)]
        (case type
          :chsk/recv (utils/swallow-errors
                      (let [[message message-data] data]
                        (handle-message app-state message message-data)))

          ;; :chsk/state is sent when the ws is opened or closed
          :chsk/state (utils/swallow-errors
                       (handle-message app-state type data))

          nil)
        (recur)))))

(defn init [app-state]
  (let [{:keys [chsk ch-recv send-fn state] :as sente-state}
        (sente/make-channel-socket! "/chsk" {:type :auto
                                             :chsk-url-fn (fn [& args]
                                                            (str (apply sente/default-chsk-url-fn args) "?tab-id=" (:tab-id @app-state)))})]
    (swap! app-state assoc :sente (assoc sente-state :ch-recv-mult (async/mult ch-recv)))
    (do-something app-state (:sente @app-state))))
