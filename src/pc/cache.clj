(ns pc.cache
  (:require [clj-statsd :as statsd]
            [clojurewerkz.spyglass.client :as c]
            [pc.profile :as profile]
            [pc.rollbar :as rollbar]))

(defonce conn (atom nil))
(defn connect! []
  (when-let [server (profile/memcached-server)]
    (reset! conn (c/bin-connection server))))

(defonce sentinel (Object.))

(defonce add-agent (agent nil
                          :error-mode :continue
                          :error-handler (fn [a e]
                                           (rollbar/report-exception e))))

(defn fast-add [connection cache-key timeout value]
  (send-off add-agent (fn [a]
                        (c/add connection cache-key timeout value))))

(defn wrap-memcache
  "Doesn't cache nils"
  [cache-key f & args]
  (if-not @conn
    (apply f args)
    (let [cached-value (deref (c/async-get @conn cache-key) 25 sentinel)]
      (cond (nil? cached-value)
            (do (statsd/increment "memcached.cache-miss")
                (let [res (apply f args)]
                  (fast-add @conn cache-key Integer/MAX_VALUE res)
                  res))
            (identical? cached-value sentinel)
            (do (statsd/increment "memcached.cache-timeout")
                (let [res (apply f args)]
                  (fast-add @conn cache-key Integer/MAX_VALUE res)
                  res))
            :else
            (do (statsd/increment "memcached.cache-hit")
                cached-value)))))

(defn init []
  (connect!))
