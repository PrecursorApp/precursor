(ns pc.stripe.dev
  (:require [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.tools.logging :as log]
            [clj-http.client :as http]
            [pc.http.urls :as urls]
            [pc.profile :as profile]
            [pc.stripe :as stripe]
            [slingshot.slingshot :refer (try+ throw+)]))

(def max-events 1000)

(defonce events (atom []))

(defonce event-agent (agent {:successes 0 :errors 0}
                            :error-mode :continue
                            :error-handler (fn [a e]
                                             (log/error e)
                                             (.printStackTrace e))))

(defn add-events [new-events]
  (swap! events (fn [e]
                  (->> (set/union (set e) (set new-events))
                    (sort-by #(get % "created"))
                    (take-last max-events)))))


(defn fetch-events []
  (when-let [new-events (seq (get (stripe/fetch-events :ending-before (-> @events last (get "id")))
                                  "data"))]
    (log/infof "fetched %s new Stripe events" (count new-events))
    (add-events new-events)
    (doseq [event new-events]
      (http/post (urls/make-url "/hooks/stripe") {:body (json/encode event)
                                                  :throw-exceptions false}))))

(defn retry-event [evt-id]
  (when-let [evt (first (filter #(= evt-id (get % "id")) @events))]
    (http/post (urls/make-url "/hooks/stripe") {:body (json/encode evt)
                                                :throw-exceptions false})))

(def interrupt (atom nil))

(defn event-fetcher-loop [a]
  (try+
   (fetch-events)
   (update-in a [:successes] inc)
   (catch Object e
     (log/error e)
     (update-in a [:errors] inc))
   (finally
     (when-not @interrupt
       (Thread/sleep (* 60 1000))
       (log/infof "fetching new Stripe events %s" a)
       (send-off *agent* event-fetcher-loop)))))

(defn start-event-fetcher-loop []
  (send-off event-agent event-fetcher-loop))

(defn init []
  (when (profile/fetch-stripe-events?)
    (start-event-fetcher-loop)))
