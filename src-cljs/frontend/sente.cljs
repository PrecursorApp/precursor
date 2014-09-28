(ns frontend.sente
  (:require-macros [cljs.core.async.macros :as asyncm :refer (go go-loop)])
  (:require [cljs.core.async :as async :refer (<! >! put! chan)]
            [taoensso.sente  :as sente :refer (cb-success?)]))

(defn subscribe-to-document [sente-state document-id]
  (if (-> sente-state :state :open?)
    #((:send-fn sente-state) [:frontend/subscribe {:document-id document-id}])
    ;; wait for connection (this probably works)
    (let [tap (async/chan (async/sliding-buffer 1))
          mult (async/mult (:ch-recv sente-state))]
      (async/tap mult tap)
      (async/take! tap
                   (fn [val]
                     ((:send-fn sente-state) [:frontend/subscribe {:document-id document-id}])
                     (async/close! tap))))))

(defn init [app-state]
  (let [{:keys [chsk ch-recv send-fn state] :as sente-state} (sente/make-channel-socket! "/chsk" {:type :auto})]
    (swap! app-state assoc :sente sente-state)
    (subscribe-to-document sente-state 123456789)))
