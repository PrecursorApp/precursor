(ns pc.views.admin
  (:require [clj-time.core :as time]
            [datomic.api :as d]
            [hiccup.core :as h]
            [pc.datomic :as pcd]
            [pc.early-access]
            [pc.models.cust :as cust-model]
            [ring.util.anti-forgery :as anti-forgery]))

(defn count-users [db time]
  (count (seq (d/datoms (d/as-of db (clj-time.coerce/to-date time))
                        :avet
                        :cust/email))))

(defn users-graph []
  (let [db (pcd/default-db)
        now (time/now)
        ;; day we got our first user!
        earliest (time/date-time 2014 11 9)
        times (take-while #(time/before? % (time/plus now (time/days 1)))
                          (iterate #(clj-time.core/plus % (clj-time.core/days 1))
                                   earliest))
        user-counts (map (partial count-users db) times)
        users-per-day (map (fn [a b] (- b a)) (cons 0 user-counts) user-counts)
        width 1000
        height 500
        x-tick-width (/ 1000 (count times))

        max-users-per-day (apply max users-per-day)
        y-tick-width (/ 500 (+ max-users-per-day (* 0.10 max-users-per-day)))

        max-users (apply max user-counts)
        y-cumulative-tick-width (/ 500 (+ max-users (* 0.10 max-users)))
        padding 20]
    (list
     [:svg {:width 1200 :height 600}
      [:rect {:x 20 :y 20 :width 1000 :height 500
              :fill "none" :stroke "black"}]
      (for [i (range 0 (inc 500) 25)]
        (list
         [:line {:x1 padding :y1 (+ padding i)
                 :x2 (+ padding 1000) :y2 (+ padding i)
                 :strokeWidth 1 :stroke "black"}]
         [:text {:x 0 :y (+ padding i)}
          (- max-users-per-day (int (* i (/ max-users-per-day 500))))]))
      (map-indexed (fn [i user-count]
                     [:circle {:cx (+ padding (* x-tick-width i))
                               :cy (+ padding (- 500 (* y-tick-width user-count)))
                               :r 5
                               :fill "blue"}])
                   users-per-day)]
     [:svg {:width 1200 :height 600}
      [:rect {:x 20 :y 20 :width 1000 :height 500
              :fill "none" :stroke "black"}]
      (for [i (range 0 (inc 500) 25)]
        (list
         [:line {:x1 padding :y1 (+ padding i)
                 :x2 (+ padding 1000) :y2 (+ padding i)
                 :strokeWidth 1 :stroke "black"}]
         [:text {:x 0 :y (+ padding i)}
          (- max-users (int (* i (/ max-users 500))))]))
      (map-indexed (fn [i user-count]
                     [:circle {:cx (+ padding (* x-tick-width i))
                               :cy (+ padding (- 500 (* y-cumulative-tick-width user-count)))
                               :r 5
                               :fill "blue"}])
                   user-counts)])))

(defn early-access-users []
  (let [db (pcd/default-db)
        requested (d/q '{:find [[?t ...]]
                         :where [[?t :flags :flags/requested-early-access]]}
                       db)
        granted (set (d/q '{:find [[?t ...]]
                            :where [[?t :flags :flags/private-docs]]}
                          db))
        not-granted (remove #(contains? granted %) requested)]
    (list
     [:style "td, th { padding: 5px; text-align: left }"]
     (if-not (seq not-granted)
       [:h4 "No users that requested early access, but don't have it."]
       (list
        [:p (str (count not-granted) " pending:")
         [:table {:border 1}
          [:tr
           [:th "Email"]
           [:th "Name"]
           [:th "Company"]
           [:th "Employee Count"]
           [:th "Use Case"]
           [:th "Grant Access (can't be undone without a repl!)"]]
          (for [cust-id (sort not-granted)
                :let [cust (cust-model/find-by-id db cust-id)
                      req (first (pc.early-access/find-by-cust db cust))]]
            [:tr
             [:td (:cust/email cust)]
             [:td (or (:cust/name cust)
                      (:cust/first-name cust))]
             [:td (:early-access-request/company-name req)]
             [:td (:early-access-request/employee-count req)]
             [:td (:early-access-request/use-case req)]
             [:td [:form {:action "/grant-early-access" :method "post"}
                   (anti-forgery/anti-forgery-field)
                   [:input {:type "hidden" :name "cust-uuid" :value (str (:cust/uuid cust))}]
                   [:input {:type "submit" :value "Grant early access"}]]]])]]))
     [:p (str (count granted) " granted:")
      [:table {:border 1}
       [:tr
        [:th "Email"]
        [:th "Name"]
        [:th "Company"]
        [:th "Employee Count"]
        [:th "Use Case"]]
       (for [cust-id (sort granted)
             :let [cust (cust-model/find-by-id db cust-id)
                   req (first (pc.early-access/find-by-cust db cust))]]
         [:tr
          [:td (:cust/email cust)]
          [:td (or (:cust/name cust)
                   (:cust/first-name cust))]
          [:td (:early-access-request/company-name req)]
          [:td (:early-access-request/employee-count req)]
          [:td (:early-access-request/use-case req)]])]])))
