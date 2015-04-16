(ns pc.util.date
  (:require [clj-time.coerce])
  (:import [java.util.Date]))

(defn default-timestamp-sec [t]
  (int (/ (timestamp-ms t) 1000)))

(defprotocol Dateable
  (timestamp-ms [t])
  (timestamp-sec [t]))

(extend java.util.Date
  Dateable
  {:timestamp-ms #(.getTime %)
   :timestamp-sec default-timestamp-sec})

(extend org.joda.time.DateTime
  Dateable
  {:timestamp-ms #(clj-time.coerce/to-long %)
   :timestamp-sec default-timestamp-sec})
