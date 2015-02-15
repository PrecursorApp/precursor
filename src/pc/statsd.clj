(ns pc.statsd
  (:require [clj-statsd :as s]
            [pc.profile :as profile]))

(defn init []
  (s/setup (profile/statsd-host) 8125))
