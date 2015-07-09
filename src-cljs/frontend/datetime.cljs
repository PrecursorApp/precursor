(ns frontend.datetime
  (:require [cljs-time.coerce :refer [from-long]]
            [cljs-time.core :as time]
            [cljs-time.format :as time-format]
            [frontend.utils :as utils :include-macros true]
            [goog.date.DateTime]
            [goog.i18n.DateTimeFormat.Format :as date-formats]
            [goog.string :as g-string]
            [goog.string.format]))

(defn unix-timestamp []
  (int (/ (goog/now) 1000)))

(def server-offset (atom nil))

(defn update-server-offset [server-date latency-ms]
  (let [newval (- (.getTime server-date) (/ latency-ms 2) (goog/now))]
    (reset! server-offset newval)))

;; TODO: use server-now for chat timestamps
(defn server-date []
  (let [offset-ms (or @server-offset 0)]
    (cljs-time.coerce/to-date
     (if (pos? offset-ms)
       (time/plus (time/now) (time/millis offset-ms))
       (time/minus (time/now) (time/millis offset-ms))))))

(def full-date-format
  (goog.i18n.DateTimeFormat. date-formats/FULL_DATE))

(def full-datetime-format
  (goog.i18n.DateTimeFormat. date-formats/FULL_DATETIME))

(def full-time-format
  (goog.i18n.DateTimeFormat. date-formats/FULL_TIME))

(def long-date-format
  (goog.i18n.DateTimeFormat. date-formats/LONG_DATE))

(def long-datetime-format
  (goog.i18n.DateTimeFormat. date-formats/LONG_DATETIME))

(def long-time-format
  (goog.i18n.DateTimeFormat. date-formats/LONG_TIME))

(def medium-date-format
  (goog.i18n.DateTimeFormat. date-formats/MEDIUM_DATE))

(def medium-datetime-format
  (goog.i18n.DateTimeFormat. date-formats/MEDIUM_DATETIME))

(def medium-time-format
  (goog.i18n.DateTimeFormat. date-formats/MEDIUM_TIME))

(def short-date-format
  (goog.i18n.DateTimeFormat. date-formats/SHORT_DATE))

(def short-datetime-format
  (goog.i18n.DateTimeFormat. date-formats/SHORT_DATETIME))

(def short-time-format
  (goog.i18n.DateTimeFormat. date-formats/SHORT_TIME))

(defn format-date [date-format date]
  (.format date-format (js/Date. date)))

(def full-date
  (partial format-date full-date-format))

(def full-datetime
  (partial format-date full-datetime-format))

(def full-time
  (partial format-date full-time-format))

(def long-date
  (partial format-date long-date-format))

(def long-datetime
  (partial format-date long-datetime-format))

(def long-time
  (partial format-date long-time-format))

(def medium-date
  (partial format-date medium-date-format))

(def medium-datetime
  (partial format-date medium-datetime-format))

(def medium-time
  (partial format-date medium-time-format))

(def short-date
  (partial format-date short-date-format))

(def short-datetime
  (partial format-date short-datetime-format))

(def short-time
  (partial format-date short-time-format))

(def medium-consistent-date-format
  (goog.i18n.DateTimeFormat. "MMM dd, yyyy"))

(def medium-consistent-date
  (partial format-date medium-consistent-date-format))

(def month-day-format
  (goog.i18n.DateTimeFormat. "MMM dd"))

(def month-day
  (partial format-date month-day-format))

(def month-day-short-format
  (goog.i18n.DateTimeFormat. "MM/dd"))

(def month-day-short
  (partial format-date month-day-short-format))

(def calendar-date-format
  (goog.i18n.DateTimeFormat. "EEE, MMM dd, yyyy 'at' hh:mma"))

(def calendar-date
  (partial format-date calendar-date-format))

(def year-month-day-date-format
  (goog.i18n.DateTimeFormat. "yyyy/MM/dd"))

(def year-month-day-date
  (partial format-date year-month-day-date-format))

(defn print-formats []
  (let [time (js/Date.)]
    (print "\n\n")
    (doseq [sym [#'full-date #'full-datetime #'full-time long-date
                 #'long-datetime #'long-time #'medium-date #'medium-datetime
                 #'medium-time #'short-date #'short-datetime #'short-time
                 #'medium-consistent-date #'calendar-date #'year-month-day-date
                 #'month-day]]
      (println (g-string/format "%-25s" (str (:name (meta sym)))) (sym time)))))


(defn date-in-ms [date]
  (let [[y m d] (map js/parseInt (.split (name date) #"-"))]
    (.getTime (js/Date. (js/Date.UTC y (dec m) (dec d) 0 0 0)))))

(def day-in-ms
  (* 1000 3600 24))

; Units of time in seconds
(def minute
  60)

(def hour
  (* minute 60))

(def day
  (* hour 24))

(def month
  (* day 30))

(def year
  (* month 12))

(defn time-ago [duration-ms]
  (let [ago (max (.floor js/Math (/ duration-ms 1000)) 0)
        interval (cond (< ago minute){:divisor 1      :unit "second" }
                       (< ago hour)  {:divisor minute :unit "minute" }
                       (< ago day)   {:divisor hour   :unit "hour"   }
                       (< ago month) {:divisor day    :unit "day"    }
                       (< ago year)  {:divisor month  :unit "month"  }
                       :else         {:divisor year   :unit "year"   })]
    (let [count (.round js/Math (/ ago (:divisor interval)))]
      (str count " "  (:unit interval) (when-not (= 1 count) "s")))))

(defn as-duration [duration]
  (if (neg? duration)
    (do (utils/mwarn "got negative duration" duration "returning 00:00")
        "00:00")
    (let [seconds (js/Math.floor (/ duration 1000))
          minutes (js/Math.floor (/ seconds 60))
          hours (js/Math.floor (/ minutes 60))
          display-seconds (g-string/format "%02d" (mod seconds 60))
          display-minutes (g-string/format "%02d" (mod minutes 60))]
      (if (pos? hours)
        (g-string/format "%s:%s:%s" hours display-minutes display-seconds)
        (g-string/format "%s:%s" display-minutes display-seconds)))))
