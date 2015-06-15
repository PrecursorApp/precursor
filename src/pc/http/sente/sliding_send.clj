(ns pc.http.sente.sliding-send
  (:require [clojure.core.async :as async]
            [pc.http.sente.common :as common]
            [pc.util.seq :refer (dissoc-in)]
            [slingshot.slingshot :refer (try+ throw+)]))

(defonce sliding-send-state (atom {:sending {:example-msg-type #{}}
                                   :messages {:example-msg-type #{}}}))

(defn pop-message [send-state-atom msg-type uid]
  (loop [val @send-state-atom]
    (if (compare-and-set! send-state-atom val (dissoc-in val [:messages msg-type uid]))
      (get-in val [:messages msg-type uid])
      (recur @send-state-atom))))

(defn lock-send [send-state-atom msg-type uid]
  (loop [val @send-state-atom]
    (let [new-val (update-in val [:sending msg-type] (fnil conj #{}) uid)]
      (if (compare-and-set! send-state-atom val new-val)
        (and (contains? (get-in new-val [:sending msg-type]) uid)
             (not (contains? (get-in val [:sending msg-type]) uid)))
        (recur @send-state-atom)))))

(defn release-send [send-state-atom msg-type uid]
  (swap! send-state-atom update-in [:sending msg-type] disj uid))

(defn pop-and-lock [send-state-atom msg-type uid]
  (loop [val @send-state-atom]
    (let [locked? (contains? (get-in val [:sending msg-type]) uid)
          message (get-in val [:messages msg-type uid])
          new-val (cond locked?
                        val

                        message
                        (-> val
                          (update-in [:sending msg-type] (fnil conj #{}) uid)
                          (dissoc-in [:messages msg-type uid]))

                        :else val)]
      (if (compare-and-set! send-state-atom val new-val)
        (when-not locked?
          message)
        (recur @send-state-atom)))))

(defn pop-or-unlock [send-state-atom msg-type uid]
  (loop [val @send-state-atom]
    (let [message (get-in val [:messages msg-type uid])
          new-val (if message
                    (dissoc-in val [:messages msg-type uid])
                    (update-in val [:sending msg-type] disj uid))]
      (if (compare-and-set! send-state-atom val new-val)
        message
        (recur @send-state-atom)))))

(def sends (atom {:attempted-sends 0 :actual-sends 0}))

(defn sliding-send
  "Sends the latest message of type msg-type, waiting for the last sent
   message to complete before sending the next one."
  [req uid [msg-type msg-body]]
  (swap! sends update-in [:attempted-sends] inc)
  (swap! sliding-send-state assoc-in [:messages msg-type uid] [msg-type msg-body])
  (when-let [latest-message (pop-and-lock sliding-send-state msg-type uid)]
    (try+
     (let [send-ch (async/chan)]
       (async/put! send-ch latest-message)
       (async/go
         (try+
          (loop [message (async/<! send-ch)]
            (when (seq message)
              (swap! sends update-in [:actual-sends] inc)
              (common/send-msg req uid message {:on-complete (fn []
                                                               (if-let [latest-message (pop-or-unlock sliding-send-state msg-type uid)]
                                                                 (async/put! send-ch latest-message)
                                                                 (async/close! send-ch)))})
              (recur (async/<! send-ch))))
          (catch Object t
            (release-send sliding-send-state msg-type uid)
            (throw+)))))
     (catch Object t
       (release-send sliding-send-state msg-type uid)
       (throw+)))))
