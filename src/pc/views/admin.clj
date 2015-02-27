(ns pc.views.admin
  (:require [clj-time.core :as time]
            [datomic.api :as d]
            [hiccup.core :as h]
            [pc.datomic :as pcd]))

(defn users-graph []
  (let [db (pcd/default-db)
        now (time/now)
        ;; day we got our first user!
        earliest (time/date-time 2014 11 9)
        times (take-while #(time/before? % (time/plus now (time/days 1)))
                          (iterate #(clj-time.core/plus % (clj-time.core/days 1))
                                   earliest))
        count-users (fn [time] (count (seq (d/datoms (d/as-of db (clj-time.coerce/to-date time))
                                                     :avet
                                                     :cust/email))))
        width 1000
        height 500
        max-users (count-users now)
        x-tick-width (/ 1000 (count times))
        y-tick-width (/ 500 (+ max-users (* 0.10 max-users)))
        padding 20]
    [:svg {:width "100%" :height "100%"}
     [:rect {:x 20 :y 20 :width 1000 :height 500
             :fill "none" :stroke "black"}]
     (map-indexed (fn [i time]
                    [:circle {:cx (+ padding (* x-tick-width i))
                              :cy (+ padding (- 500 (* y-tick-width (count-users time))))
                              :r 5
                              :fill "blue"}])
                  times)]))

(defn early-access-users []
  (let [db (pcd/default-db)]
    [:p (str (d/q '{:find [?t]
                    :where [[?t :flags :flags/requested-early-access]]}
                  db))]))
