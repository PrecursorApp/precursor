(ns frontend.sente
  (:require-macros [cljs.core.async.macros :as asyncm :refer (go go-loop)])
  (:require [cljs.core.async :as async :refer (<! >! put! chan)]
            [frontend.utils :as utils :include-macros true]
            [taoensso.sente  :as sente :refer (cb-success?)]))

(defn send-msg [sente-state message]
  (if (-> sente-state :state deref :open? utils/inspect)
    ((:send-fn sente-state) (utils/inspect message))
    ;; wait for connection (this probably works)
    (let [tap (async/chan (async/sliding-buffer 1))
          mult (async/mult (:ch-recv sente-state))]
      (async/tap mult tap)
      (async/take! tap
                   (fn [val]
                     ((:send-fn sente-state) message)
                     (async/close! tap))))))

(defn subscribe-to-document [sente-state document-id]
  (send-msg sente-state [:frontend/subscribe {:document-id document-id}]))

(defn do-something [sente-state]
  (let [tap (async/chan (async/sliding-buffer 1))
         mult (async/mult (:ch-recv sente-state))]
    (async/tap mult tap)
    (go-loop []
             (when-let [val (<! tap)]
               (utils/inspect val)
               (recur)))))

(defn init [app-state]
  (let [{:keys [chsk ch-recv send-fn state] :as sente-state} (sente/make-channel-socket! "/chsk" {:type :auto})]
    (swap! app-state assoc :sente sente-state)
    (subscribe-to-document sente-state 17592186046465)
    (do-something sente-state)))
