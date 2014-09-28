(ns frontend.sensa
  (:require-macros [cljs.core.async.macros :as asyncm :refer (go go-loop)])
  (:require [cljs.core.async :as async :refer (<! >! put! chan)]
            [taoensso.sente  :as sente :refer (cb-success?)]))

(defn init [state]
  (let [{:keys [chsk ch-recv send-fn state] :as sensa-state} (sente/make-channel-socket! "/chsk" {:type :auto})]
    (swap! state assoc :sensa sensa-state)))
