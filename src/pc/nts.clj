(ns pc.nts
  (:require [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [clj-http.client :as http]
            [clj-time.core :as time]
            [clj-time.format]
            [pc.twilio :as twilio]
            [pc.rollbar :as rollbar]
            [pc.utils]
            [slingshot.slingshot :refer (try+ throw+)])
  (:import [java.util.concurrent LinkedBlockingQueue]
           [com.google.common.base Throwables]))

(defn fetch-token []
  (-> (http/post (str twilio/base-url "Tokens.json")
                 {:basic-auth [twilio/sid twilio/auth-token]})
    :body
    cheshire.core/decode
    (#(assoc % :expires (time/plus (clj-time.format/parse (get % "date_created"))
                                   (time/seconds (Integer/parseInt (get % "ttl"))))))))

(defn pop-atom [atom]
  (loop [val @atom]
    (if (compare-and-set! atom val (rest val))
      (first val)
      (recur @atom))))

(defn pmap-n
  "Kind of like pmap, but runs n threads at once and idle threads don't have to
  wait for the entire block to finish. Doesn't preserve order and isn't lazy."
  [n f coll]
  (let [items (atom coll)
        results (atom ())]
    (->> (range n)
      (map (fn [_]
             (future
               (loop [item (pop-atom items)]
                 (when item
                   (swap! results conj (f item))
                   (recur (pop-atom items)))))))
      doall
      (map deref)
      doall)
    @results))

(def min-token-count 1000)

(defonce tokens (atom []))

(defn get-token []
  (pop-atom tokens))

(defn token-expired? [token]
  (time/after? (time/now) (:expires token)))

(defonce token-fetcher-agent (agent nil
                                    :error-mode :continue
                                    :error-handler (fn [a e]
                                                     (log/error e)
                                                     (rollbar/report-exception e))))

(defn fetch-tokens [_]
  (let [fetch-token-count (- min-token-count (count @tokens))]
    (log/infof "no need to fetch tokens from Twilio, skipping")
    (when (pos? fetch-token-count)
      (log/infof "fetching %s nts tokens from Twilio" (max 10 fetch-token-count))
      (let [new-tokens (pmap-n 10 (fn [i] (fetch-token)) (range (max 10 fetch-token-count)))]
        (swap! tokens #(apply conj % new-tokens))))))


(defn setup-token-fetcher []
  (add-watch tokens :token-fetcher (fn [_ _ old new]
                                     (when (< (count new) min-token-count)
                                       (log/infof "sending-off agent to fetch tokens from Twilio")
                                       (send-off token-fetcher-agent fetch-tokens)))))

(defn clean-old-tokens []
  (let [earliest (time/minus (time/now)
                             (time/hours 12))]
    (swap! tokens (fn [tokens] (filterv (fn [t]
                                          (time/before? (:expires t)
                                                       earliest))
                                        tokens)))))

(defn setup-clean-old-tokens []
  (pc.utils/safe-schedule {:minute [2]} #'clean-old-tokens))

(defn init []
  (log/infof "fetching tokens")
  (time
   (reset! tokens (pmap-n 10 (fn [i] (fetch-token)) (range min-token-count))))
  (setup-token-fetcher)
  (setup-clean-old-tokens))
