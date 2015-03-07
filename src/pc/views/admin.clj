(ns pc.views.admin
  (:require [clj-time.core :as time]
            [datomic.api :as d]
            [hiccup.core :as h]
            [pc.datomic :as pcd]
            [pc.early-access]
            [pc.http.urls :as urls]
            [pc.models.cust :as cust-model]
            [ring.util.anti-forgery :as anti-forgery]))

(defn count-users [db time]
  (count (seq (d/datoms (d/as-of db (clj-time.coerce/to-date time))
                        :avet
                        :cust/email))))

(defn title [{:keys [user-count time]}]
  (str user-count " " (time/month time) "/" (time/day time)))

(defn users-graph []
  (let [db (pcd/default-db)
        now (time/now)
        ;; day we got our first user!
        earliest (time/from-time-zone (time/date-time 2014 11 9)
                                      (time/time-zone-for-id "America/Los_Angeles"))
        times (take-while #(time/before? % (time/plus now (time/days 1)))
                          (iterate #(clj-time.core/plus % (clj-time.core/days 1))
                                   earliest))

        user-counts (map (fn [time]
                           {:time time
                            :user-count (count-users db time)})
                         times)
        users-per-day (map (fn [a b] {:time (:time b)
                                      :user-count (- (:user-count b)
                                                     (:user-count a))})
                           (cons {:user-count 0} user-counts)
                           user-counts)
        width 1000
        height 500
        x-tick-width (/ 1000 (count times))

        max-users-per-day (apply max (map :user-count users-per-day))
        y-tick-width (/ 500 max-users-per-day)

        max-users (apply max (map :user-count user-counts))
        y-cumulative-tick-width (/ 500 max-users)
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
         [:text {:x (+ (* 1.5 padding) 1000) :y (+ padding i)}
          (- max-users-per-day (int (* i (/ max-users-per-day 500))))]))
      (map-indexed (fn [i user-count]
                     [:g
                      [:circle {:cx (+ padding (* x-tick-width i))
                                :cy (+ padding (- 500 (* y-tick-width (:user-count user-count))))
                                :r 5
                                :fill "blue"
                                }]
                      [:title (title user-count)]])
                   users-per-day)]
     [:svg {:width 1200 :height 600}
      [:rect {:x 20 :y 20 :width 1000 :height 500
              :fill "none" :stroke "black"}]
      (for [i (range 0 (inc 500) 25)]
        (list
         [:line {:x1 padding :y1 (+ padding i)
                 :x2 (+ padding 1000) :y2 (+ padding i)
                 :strokeWidth 1 :stroke "black"}]
         [:text {:x (+ (* 1.5 padding) 1000) :y (+ padding i)}
          (- max-users (int (* i (/ max-users 500))))]))
      (map-indexed (fn [i user-count]
                     [:g
                      [:circle {:cx (+ padding (* x-tick-width i))
                                :cy (+ padding (- 500 (* y-cumulative-tick-width (:user-count user-count))))
                                :r 5
                                :fill "blue"}]
                      [:title (title user-count)]])
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
             [:td (h/h (:cust/email cust))]
             [:td (h/h (or (:cust/name cust)
                           (:cust/first-name cust)))]
             [:td (h/h (:early-access-request/company-name req))]
             [:td (h/h (:early-access-request/employee-count req))]
             [:td (h/h (:early-access-request/use-case req))]
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
          [:td (h/h (:cust/email cust))]
          [:td (h/h (or (:cust/name cust)
                        (:cust/first-name cust)))]
          [:td (h/h (:early-access-request/company-name req))]
          [:td (h/h (:early-access-request/employee-count req))]
          [:td (h/h (:early-access-request/use-case req))]])]])))

(defn format-runtime [ms]
  (let [h (int (Math/floor (/ ms (* 1000 60 60))))
        m (int (Math/floor (mod (/ ms 1000 60) 60)))
        s (int (Math/floor (mod (/ ms 1000) 60)))]
    (format "%s:%s:%s" h m s)))

(defn clients [client-stats document-subs]
  [:div
   [:form {:action "/refresh-client-stats" :method "post"}
    (anti-forgery/anti-forgery-field)
    [:input {:type "hidden" :name "refresh-all" :value true}]
    [:input {:type "submit" :value "Refresh all (don't do this too often)"}]]
   [:style "td, th { padding: 5px; text-align: left }"]
   [:table {:border 1}
    [:tr
     [:th "Document (subs)"]
     [:th "User"]
     [:th "Action"]
     [:th "Code version"]
     [:th "Chat #"]
     [:th "unread-chat #"]
     [:th "TX #"]
     [:th "layer count"]
     [:th "logged-in?"]
     [:th "run-time (h:m:s)"]
     [:th "subscriber-count"]
     [:th "visibility"]]
    (for [[client-id stats] (reverse (sort-by (comp :last-update second) client-stats))
          :let [doc-id (get-in stats [:document :db/id])]]
      [:tr
       [:td
        [:a {:href (urls/doc-svg doc-id)}
         [:img {:style "width:100;height:100;"
                :src (urls/doc-svg doc-id)}]]]
       [:td (h/h (get-in stats [:cust :cust/email]))]
       [:td [:form {:action "/refresh-client-stats" :method "post"}
             (anti-forgery/anti-forgery-field)
             [:input {:type "hidden" :name "client-id" :value (h/h client-id)}]
             [:input {:type "submit" :value "refresh"}]]]
       [:td (let [v (h/h (get-in stats [:stats :code-version]))]
              [:a {:href (str "https://github.com/dwwoelfel/precursor/commit/" v)}
               v])]
       [:td (h/h (get-in stats [:stats :chat-count]))]
       [:td (h/h (get-in stats [:stats :unread-chat-count]))]
       [:td (h/h (get-in stats [:stats :transaction-count]))]
       [:td (h/h (get-in stats [:stats :layer-count]))]
       [:td (h/h (get-in stats [:stats :logged-in?]))]
       [:td (h/h (some-> (get-in stats [:stats :run-time-millis]) format-runtime))]
       [:td (count (get document-subs doc-id))]
       [:td (let [visibility (h/h (get-in stats [:stats :visibility]))]
              (list visibility
                    (when (= "hidden" visibility)
                      [:form {:action "/refresh-client-browser" :method "post"}
                       (anti-forgery/anti-forgery-field)
                       [:input {:type "hidden" :name "client-id" :value (h/h client-id)}]
                       [:input {:type "submit" :value "refresh browser"}]])))]])]])
