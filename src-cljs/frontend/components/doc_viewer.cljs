(ns frontend.components.doc-viewer
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [cljs-time.core :as time]
            [datascript :as d]
            [frontend.async :refer [put!]]
            [frontend.components.common :as common]
            [frontend.datascript :as ds]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [goog.date]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]])
  (:import [goog.ui IdGenerator]))

(def day-of-week
  {1 "Monday"
   2 "Tuesday"
   3 "Wednesday"
   4 "Thursday"
   5 "Friday"
   6 "Saturday"
   7 "Sunday"})

(defn date->bucket [date]
  (let [time (goog.date.DateTime. date)
        start-of-day (doto (goog.date.DateTime.)
                       (.setHours 0)
                       (.setMinutes 0)
                       (.setSeconds 0)
                       (.setMilliseconds 0))]
    (cond
     (time/after? time start-of-day) "today"
     (time/after? time (time/minus start-of-day (time/days 1))) "yesterday"
     (time/after? time (time/minus start-of-day (time/days 6))) (day-of-week (time/day-of-week time))
     (time/after? time (time/minus start-of-day (time/days 14))) "last week"
     :else "a while ago")))


(defn doc-viewer [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            touched-docs (get-in app [:cust :touched-docs])
            ;; Not showing created for now, since we haven't been storing that until recently
            created-docs (get-in app [:cust :created-docs])
            docs (take 100 (reverse (sort-by :last-updated-instant (filter :last-updated-instant touched-docs))))]
        (html
         [:div.menu-prompt {:class (str "menu-prompt-" "doc-viewer")}
          [:div.menu-header
           [:a.menu-close {:on-click #(cast! :overlay-closed)
                           :role "button"}
            (common/icon :times)]]
          [:div.menu-prompt-body {:style {:overflow "scroll"}}
           [:h2 "Your Docs"]
           [:a {:on-click #(cast! :touched-fetched)
                :role "button"}
            "Fetch docs"]

           (for [[time-bucket bucket-docs] (reverse (sort-by #(:last-updated-instant (first (last %)))
                                                             (group-by #(date->bucket (:last-updated-instant %)) docs)))]
             (list*
              [:div.time-bucket (str "Updated " time-bucket)]

              (for [doc bucket-docs]
                [:a {:href (str "/document/" (:db/id doc))}
                 [:img {:src (str "/document/" (:db/id doc) ".svg")
                        :style {:border "1px solid black"
                                :margin-bottom 20}
                        :width 300
                        :height 300}]])))]])))))
