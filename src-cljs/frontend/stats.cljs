(ns frontend.stats
  (:require [goog.labs.dom.PageVisibilityMonitor]
            [frontend.models.chat :as chat-model]
            [frontend.models.layer :as layer-model]
            [frontend.state :as state]
            [frontend.utils :as utils]))

(defn run-time-millis []
  (- (.getTime (js/Date.))
     (.getTime (js/Date. (aget js/window "Precursor" "page-start")))))

(defn code-version []
  (aget js/window "Precursor" "manifest-version"))

(defn performance []
  (when-let [perf js/window.performance]
    (js->clj (js/JSON.parse (js/JSON.stringify perf)))))

(defn gather-stats [app-state]
  (let [db @(:db app-state)]
    {:run-time-millis (run-time-millis)
     :code-version (code-version)
     :visibility (.getVisibilityState (goog.labs.dom.PageVisibilityMonitor.))
     :unread-chat-count (->> (or (get-in app-state (state/last-read-chat-time-path (:document/id app-state)))
                                 (js/Date. 0))
                          (chat-model/compute-unread-chat-count db))
     :layer-count (layer-model/find-count db)
     :chat-count (chat-model/find-count db)
     :transaction-count (-> app-state :undo-state deref :transactions count)
     :subscriber-count (->> app-state :subscribers :info (remove (comp :hide-in-list? second)) count)
     :camera (select-keys (:camera app-state) [:x :y :zf])
     :mouse (:mouse app-state)
     :logged-in? (boolean (seq (:cust app-state)))
     :performance (performance)}))
