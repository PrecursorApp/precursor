(ns pc.util.date
  (:require [clj-time.core :as time]
            [clj-time.coerce])
  (:import [java.util.Date]))

(defprotocol Dateable
  (timestamp-ms [t])
  (timestamp-sec [t]))

(defn default-timestamp-sec [t]
  (int (/ (timestamp-ms t) 1000)))

(extend java.util.Date
  Dateable
  {:timestamp-ms #(.getTime %)
   :timestamp-sec default-timestamp-sec})

(extend org.joda.time.DateTime
  Dateable
  {:timestamp-ms #(clj-time.coerce/to-long %)
   :timestamp-sec default-timestamp-sec})

(defn min-time
  ([x] x)
  ([x y] (if (time/after? x y) y x))
  ([x y & more] (reduce min-time (min-time x y) more)))

(defn max-time
  ([x] x)
  ([x y] (if (time/after? x y) x y))
  ([x y & more] (reduce max-time (max-time x y) more)))
