(ns frontend.utils.date
  (:require [cljs-time.core :as time]
            [cljs-time.format]
            [goog.date]))

(defn day-month-year [date]
  (cljs-time.format/unparse (cljs-time.format/formatter "MMM d, yyyy") date))

(def day-of-week
  {1 "Monday"
   2 "Tuesday"
   3 "Wednesday"
   4 "Thursday"
   5 "Friday"
   6 "Saturday"
   7 "Sunday"})

(defn date->bucket [date & {:keys [sentence?]
                            :or {sentence? false}}]
  (let [time (goog.date.DateTime. date)
        start-of-day (doto (goog.date.DateTime.)
                       (.setHours 0)
                       (.setMinutes 0)
                       (.setSeconds 0)
                       (.setMilliseconds 0))]
    (cond
     (time/after? time start-of-day) "today"
     (time/after? time (time/minus start-of-day (time/days 1))) "yesterday"
     (time/after? time (time/minus start-of-day (time/days 6))) (str (when sentence? "on ") (day-of-week (time/day-of-week time)))
     (time/after? time (time/minus start-of-day (time/days 14))) "last week"
     :else (str (when sentence? "on ") (day-month-year time)))))
