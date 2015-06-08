(ns pc.views.admin
  (:require [cheshire.core :as json]
            [clj-time.coerce]
            [clj-time.format]
            [clj-time.core :as time]
            [clojure.string]
            [datomic.api :as d]
            [hiccup.core :as h]
            [pc.datomic :as pcd]
            [pc.early-access]
            [pc.gcs]
            [pc.auth :as auth]
            [pc.http.sente :as sente]
            [pc.http.urls :as urls]
            [pc.models.cust :as cust-model]
            [pc.models.chat :as chat-model]
            [pc.models.doc :as doc-model]
            [pc.models.plan :as plan-model]
            [pc.models.permission :as permission-model]
            [pc.models.team :as team-model]
            [pc.replay :as replay]
            [pc.stripe.dev :as stripe-dev]
            [ring.util.anti-forgery :as anti-forgery]))

(defn cust-link [cust]
  [:a {:href (str "/user/" (:cust/email cust))}
   (:cust/email cust)])

(defn team-link [team]
  [:a {:href (str "/team/" (:team/subdomain team))}
   (:team/subdomain team)])

(defn doc-link [doc]
  [:a {:href (urls/from-doc doc)}
   (:db/id doc)])

(defn interesting [docs]
  [:div.interesting
   (if-not (seq docs)
     [:p "Nothing to show"])
   (for [doc docs]
     [:div.doc-preview
      [:a {:href (str "/document/" (:db/id doc))}
       [:img {:src (urls/svg-from-doc doc)}]]])])

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

(defn growth-graph [user-counts]
  [:table {:border 1}
   [:tr
    [:th "Period"]
    [:th "Users"]
    [:th "Growth"]
    [:th "New users"]
    [:th "New growth"]
    [:th "avg/day"]]
   (reverse
    (for [[way-before before after] (partition 3 1 (cons {:user-count 0} user-counts))]
      [:tr
       [:td (format "%s-%s"
                    (clj-time.format/unparse (clj-time.format/formatter "MMM dd") (:time before))
                    (clj-time.format/unparse (clj-time.format/formatter "MMM dd") (:time after)))]
       [:td (:user-count after)]
       [:td (when (pos? (:user-count before))
              (format "%.2f%%" (float (* 100 (/ (- (:user-count after) (:user-count before))
                                                (:user-count before))))))]
       [:td (- (:user-count after) (:user-count before))]
       [:td (when (pos? (- (:user-count before) (:user-count way-before)))
              (format "%.2f%%" (float (* 100 (/ (- (- (:user-count after) (:user-count before))
                                                   (- (:user-count before) (:user-count way-before)))
                                                (- (:user-count before) (:user-count way-before)))))))]
       [:td (int (/ (- (:user-count after) (:user-count before))
                    (time/in-days (time/interval (:time before) (:time after)))))]]))])

(defn growth []
  (let [db (pcd/default-db)
        earliest (time/date-time 2014 11)
        now (time/now)
        times (take-while #(time/before? % (time/plus now (time/months 1)))
                          (map #(time/plus earliest (time/months %))
                               (range)))
        user-counts (map (fn [time]
                           {:time time
                            :user-count (count-users db time)})
                         times)
        rolling-times (reverse (take-while #(time/after? % earliest)
                                           (map #(time/minus now (time/months %))
                                                (range))))
        rolling-counts (map (fn [time]
                              {:time time
                               :user-count (count-users db time)})
                            rolling-times)
        weekly-times (take-while #(time/before? % (time/plus now (time/weeks 1)))
                                 (map #(time/plus earliest (time/weeks %))
                                      (range)))
        weekly-user-counts (map (fn [time]
                                  {:time time
                                   :user-count (count-users db time)})
                                weekly-times)
        weekly-rolling-times (reverse (take-while #(time/after? % earliest)
                                                  (map #(time/minus now (time/weeks %))
                                                       (range))))
        weekly-rolling-counts (map (fn [time]
                                     {:time time
                                      :user-count (count-users db time)})
                                   weekly-rolling-times)]
    (list
     [:style "td, th { padding: 5px; text-align: left } td {text-align: right} tr {vertical-align: top}"]
     [:table
      [:tr
       [:th [:h4 "Growth per month"]]
       [:th [:h4 "Growth per rolling month"]]]
      [:tr
       [:td [:p (growth-graph user-counts)]]
       [:td [:p (growth-graph rolling-counts)]]]]
     [:table
      [:tr
       [:th [:h4 "Growth per week"]]
       [:th [:h4 "Growth per rolling week"]]]
      [:tr
       [:td [:p (growth-graph weekly-user-counts)]]
       [:td [:p (growth-graph weekly-rolling-counts)]]]])))

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
             [:td [:a {:href (str "/user/" (h/h (:cust/email cust)))}
                   (h/h (:cust/email cust))]]
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
          [:td [:a {:href (str "/user/" (h/h (:cust/email cust)))}
                (h/h (:cust/email cust))]]
          [:td (h/h (or (:cust/name cust)
                        (:cust/first-name cust)))]
          [:td (h/h (:early-access-request/company-name req))]
          [:td (h/h (:early-access-request/employee-count req))]
          [:td (h/h (:early-access-request/use-case req))]])]])))


(defn teams []
  (let [db (pcd/default-db)
        teams (map #(d/entity db (:e %))
                   (d/datoms db :aevt :team/subdomain))]
    (list
     [:style "td, th { padding: 5px; text-align: left }"]
     (if-not (seq teams)
       [:h4 "Couldn't find any teams :("]
       (list
        [:p (str (count teams) " teams:")
         [:table {:border 1}
          [:tr
           [:th "subdomain"]
           [:th "doc-count"]
           [:th "status"]
           [:th "trial-end"]
           [:th "coupon"]
           [:th "creator"]
           [:th "active"]
           [:th "members"]]
          (for [team teams
                :let [plan (:team/plan team)]]
            [:tr
             [:td (team-link team)]
             [:td (count (seq (d/datoms db :vaet (:db/id team) :document/team)))]
             [:td (cond (:plan/paid? plan) "paid"
                        (not (plan-model/trial-over? plan)) "trial"
                        :else "trial expired")]
             [:td (:plan/trial-end plan)]
             [:td (:discount/coupon (:team/plan team))]
             [:td (cust-link (:team/creator team))]
             [:td (let [active (:plan/active-custs plan)]
                    (interleave (map cust-link active)
                                (repeat " ")))]
             [:td (let [permissions (permission-model/find-by-team db team)]
                    (interleave (map (comp cust-link :permission/cust-ref) permissions)
                                (repeat " ")))]])]])))))

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
     [:th "canvas-size"]
     [:th "subscriber-count"]
     [:th "visibility"]]
    (for [[client-id stats] (reverse (sort-by (comp :last-update second) client-stats))
          :let [doc-id (get-in stats [:document :db/id])]]
      [:tr
       [:td
        [:div
         [:a {:href (str "/document/" doc-id)}
          [:img {:style "width:100;height:100;"
                 :src (urls/doc-svg doc-id)}]]]
        [:div doc-id]]
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
       [:td (h/h (when-let [canvas-size (get-in stats [:stats :canvas-size])]
                   (str (:width canvas-size) "x" (:height canvas-size))))]
       [:td (count (get document-subs doc-id))]
       [:td (let [visibility (h/h (get-in stats [:stats :visibility]))]
              (list visibility
                    (when (= "hidden" visibility)
                      [:form {:action "/refresh-client-browser" :method "post"}
                       (anti-forgery/anti-forgery-field)
                       [:input {:type "hidden" :name "client-id" :value (h/h client-id)}]
                       [:input {:type "submit" :value "refresh browser"}]])))]])]])

(defn users []
  (let [db (pcd/default-db)
        active (time (doall (map (partial cust-model/find-by-uuid db)
                                 (d/q '[:find [?uuid ...]
                                        :where [?t :cust/uuid ?uuid]]
                                      (d/since db (clj-time.coerce/to-date
                                                   (time/minus
                                                    (time/now)
                                                    (time/days 1))))))))]
    [:div
     [:h3 (str (count active) " users active in the last day")]
     [:style "td, th { padding: 5px; text-align: left }"]
     [:table {:border 1}
      [:tr
       [:th "Email"]
       [:th "Touched docs count"]]
      (for [u-info (reverse
                    (sort-by :doc-count
                             (map (fn [cust]
                                    {:email (:cust/email cust)
                                     :doc-count (d/q '{:find [(count ?doc-id) .]
                                                       :in [$ ?uuid]
                                                       :where [[?t :cust/uuid ?uuid]
                                                               [?t :transaction/document ?doc-id]]}
                                                     db (:cust/uuid cust))})
                                  active)))]
        [:tr
         [:td [:a {:href (str "/user/" (:email u-info))}
               (:email u-info)]]
         [:td (:doc-count u-info)]])]]))

(defmulti render-cust-prop (fn [attr value] attr))

(defmethod render-cust-prop :default
  [attr value]
  (h/h value))

(defmethod render-cust-prop :cust/http-session-key
  [attr value]
  "")

(defmethod render-cust-prop :cust/clips
  [attr value]
  (str (count value) ", " (count (filter :clip/important? value)) " important"))

(defmethod render-cust-prop :google-account/avatar
  [attr value]
  [:img {:src value :width 100 :height 100}])

(defmethod render-cust-prop :google-account/sub
  [attr value]
  [:a {:href (str "https://plus.google.com/" value)}
   value])

(defmethod render-cust-prop :cust/guessed-dribbble-username
  [attr value]
  [:a {:href (str "https://dribbble.com/" value)}
   value])

(defn user-info [cust]
  (list
   [:style "td, th { padding: 5px; text-align: left }"]
   [:table {:border 1}
    (for [[k v] (sort-by first (into {} cust))]
      [:tr
       [:td (h/h (str k))]
       [:td (render-cust-prop k v)]])
    [:tr
     [:td "Teams"]
     [:td (interleave (->> cust
                        (permission-model/find-team-permissions-for-cust (pcd/default-db))
                        (map :permission/team)
                        (sort-by :team/subdomain)
                        (map team-link))
                      (repeat " "))]]]))

(defn doc-info [doc auth]
  (let [db (pcd/default-db)]
    (list
     [:style "td, th { padding: 5px; text-align: left }"]
     (when-not (contains? (:document/tags doc) "admin/interesting")
       [:form {:action (str "/document/" (:db/id doc) "/mark-interesting")
               :method "post"}
        (anti-forgery/anti-forgery-field)
        [:input {:type "submit" :value "Mark interesting"}]])
     [:table {:border 1}
      [:tr
       [:td "Tags"]
       [:td (:document/tags doc)]]
      [:tr
       [:td "Chat count"]
       [:td (count (seq (d/datoms db :vaet (:db/id doc) :chat/document)))]]
      [:tr
       [:td "Layer count"]
       [:td (count (seq (d/datoms db :vaet (:db/id doc) :layer/document)))]]
      [:tr
       [:td {:title "Number of clients connected right now"}
        "Client count"]
       [:td (count (get @sente/document-subs (:db/id doc)))]]
      [:tr
       [:td "Owner"]
       [:td (some->> doc
              :document/creator
              (cust-model/find-by-uuid db)
              :cust/email
              ((fn [e]
                 [:a {:href (str "/user/" e)} e])))]]
      (let [emails (d/q '{:find [[?email ...]]
                          :in [$ ?doc-id]
                          :where [[?t :transaction/document ?doc-id]
                                  [?t :cust/uuid ?uuid]
                                  [?u :cust/uuid ?uuid]
                                  [?u :cust/email ?email]]}
                        db (:db/id doc))]
        (list
         [:tr
          [:td "User Count"]
          [:td (count emails)]]
         [:tr
          [:td (str "Users")]
          [:td (for [email emails]
                 [:span [:a {:href (str "/user/" email)}
                         email]
                  " "])]]))
      [:tr
       [:td "Created"]
       [:td (doc-model/created-time db (:db/id doc))]]
      [:tr
       [:td "Last updated"]
       [:td (doc-model/last-updated-time db (:db/id doc))]]
      [:tr
       [:td "Full SVG link"]
       [:td [:a {:href (urls/svg-from-doc doc)}
             (:db/id doc)]]]
      [:tr
       [:td "Replay helper"]
       [:td [:a {:href (str "/replay-helper/" (:db/id doc))}
             (:db/id doc)]]]
      [:tr
       [:td "Live doc url"]
       [:td
        "Tiny b/c you could be intruding "
        [:a {:href (urls/from-doc doc)
             :style "font-size: 0.5em"}
         (:db/id doc)]]]]
     [:a {:href (urls/svg-from-doc doc)}
      [:img {:src (urls/svg-from-doc doc)
             :style "width: 100%; height: 100%"}]]
     (when (auth/has-document-permission? db doc auth :read)
       (list
        [:h3 "Chats"]
        (let [find-cust (memoize (partial cust-model/find-by-uuid db))]
          [:table
           (for [chat (sort-by :server/timestamp (chat-model/find-by-document db doc))]
             [:tr
              [:td (:server/timestamp chat)]
              (let [email (some->> chat :cust/uuid find-cust :cust/email)]
                [:td (if email
                       [:a {:href (str "/user/" email)}
                        email]
                       (some-> chat :session/uuid str (subs 0 5)))])
              [:td (h/h (:chat/body chat))]])]))))))

(defn team-info [team]
  (let [db (pcd/default-db)
        team-docs (map (comp (partial d/entity db) :e)
                       (d/datoms db :vaet (:db/id team) :document/team))
        team-txes (team-model/team-txes db team)]
    (def myteamdocs team-docs)
    (list
     [:style "td, th { padding: 5px; text-align: left }"]
     [:table {:border 1}
      [:tr
       [:td "Subdomain"]
       [:td (:team/subdomain team)]]
      [:tr
       [:td "Creator"]
       [:td (cust-link (:team/creator team))]]
      (let [active (:plan/active-custs (:team/plan team))]
        [:tr
         [:td (str "Active members (" (count active) ")")]
         [:td (interleave (map cust-link (sort-by :cust/email active))
                          (repeat " "))]])
      (let [members (map :permission/cust-ref (permission-model/find-by-team db team))]
        [:tr
         [:td (str "All members (" (count members) ")")]
         [:td (interleave (map cust-link (sort-by :cust/email members))
                          (repeat " "))]])
      [:tr
       [:td "Created instant"]
       [:td (d/q '[:find ?inst .
                   :in $ ?uuid
                   :where
                   [?e :team/uuid ?uuid ?tx]
                   [?tx :db/txInstant ?inst]]
                 db (:team/uuid team))]]
      [:tr
       [:td "Doc count"]
       [:td (count team-docs)]]

      [:tr
       [:td "tx count"]
       [:td (count team-txes)]]

      [:tr
       [:td "Last activity"]
       [:td (:db/txInstant (last (sort-by :db/txInstant team-txes)))]]

      [:tr
       [:td "Plan url"]
       [:td (let [url (urls/team-plan team)]
              [:a {:href url} url])]]
      [:tr
       [:td "Add users url"]
       [:td (let [url (urls/team-add-users team)]
              [:a {:href url} url])]]
      [:tr
       [:td "Trial end"]
       [:td (:plan/trial-end (:team/plan team))]]]
     (interesting team-docs))))

(defn upload-files []
  (let [bucket "precursor"
        acl "public-read"
        expiration (time/plus (time/now) (time/days 1))
        object-prefix ""
        policy (pc.gcs/generate-policy-document {:bucket bucket
                                                 :acl acl
                                                 :expiration expiration
                                                 :object-prefix object-prefix})]
    (list
     [:style "body { margin: 2em;}"]
     [:form {:action (format "https://%s.storage.googleapis.com" bucket)
             :method "post"
             :enctype "multipart/form-data"
             :onSubmit (format "this.submit(); event.preventDefault(); window.setTimeout(function () { window.location.assign('https://%s.storage.googleapis.com/' + document.getElementById('key').value)}, 1000)"
                               bucket)
             }
      [:p "Choose path (careful not to override an existing path):"]
      [:p
       (format "https://%s.storage.googleapis.com/" bucket)
       [:input {:type "text"
                :name "key"
                :id "key"
                :value ""
                :style "width: 500px"}]]
      [:input {:type "hidden" :name "bucket" :value bucket}]
      [:input {:type "hidden" :name "GoogleAccessId" :value pc.gcs/access-id}]
      [:input {:type "hidden" :name "acl" :value acl}]
      [:input {:type "hidden" :name "policy" :value policy}]
      [:input {:type "hidden" :name "signature" :value (pc.gcs/sign policy)}]
      ;; must be last (!)
      [:p "Upload file:"]
      [:p [:input {:name "file" :type "file"}]]
      [:input {:type "submit" :value "Upload"}]])))

(defn replay-helper [doc]
  (let [txids (replay/get-document-tx-ids (pcd/default-db) doc)
        initial-tx (last txids)
        replace-img (fn [txid]
                      (format "document.getElementById('img').src = document.getElementById('img').src.replace(/(as-of=)(\\d+)/, function(s, m1, m2) { return m1 + '%s'});"
                              txid))]
    (list
     [:div {:style "display: inline-block; width: 19%; vertical-align: top;"}
      [:span "Current: " [:span#current-tx initial-tx]]
      [:ol {:style "max-height: 50vh; overflow: scroll"}
       (for [txid txids]
         [:li
          [:input {:style "cursor:pointer;border: none;outline: none"
                   :value txid
                   :onClick (str (format "document.getElementById('current-tx').textContent = '%s';"
                                         txid)
                                 (replace-img txid)
                                 "this.select()")}]])]]
     [:img#img {:src (urls/svg-from-doc doc :query {:as-of initial-tx})
                :width "80%"
                :style "border: 1px solid rgba(0,0,0,0.3)"}]
     [:script {:type "text/javascript"}
      (format
       "var txids = %s;
        for (var i=0;i<txids.length;i++) {
          window.setTimeout((function(i) {
                             return function() { document.getElementById('img').src = document.getElementById('img').src.replace(/(as-of=)(\\d+)/, function(s, m1, m2) {return m1 + txids[i]}); document.getElementById('current-tx').textContent = txids[i];}
                            })(i), i * 250)
        }"
       (str "[" (clojure.string/join "," txids) "]"))])))

(defn stripe-events []
  (list
   [:style "body { padding: 1em }"]
   (for [event (reverse @stripe-dev/events)]
     [:div.event
      [:div.event-type
       [:span (get-in event ["type"])]
       [:form {:style "display: inline-block; padding-left: 1em"
               :method "post"
               :action (str "/retry-stripe-event/" (get-in event ["id"]))}
        (anti-forgery/anti-forgery-field)
        [:input {:type "submit" :value "retry"}]]]
      [:pre.event-body (h/h (json/encode event {:pretty true}))]])))

(defn modify-billing []
  [:div {:style "padding: 40px"}
   [:div {:style "margin-top: 1em"}
    "Add team member to team"
    [:form {:method "post" :action "/add-team-cust"}
     (anti-forgery/anti-forgery-field)
     [:label "Team subdomain "]
     [:input {:type "text" :name "team-subdomain"}]
     [:label "Cust email "]
     [:input {:type "text" :name "email"}]
     [:div
      [:input {:type "submit" :value "Add"}]]]]
   [:div {:style "margin-top: 1em"}
    "Remove team member from team"
    [:form {:method "post" :action "/remove-team-cust"}
     (anti-forgery/anti-forgery-field)
     [:label "Team subdomain "]
     [:input {:type "text" :name "team-subdomain"}]
     [:label "Cust email "]
     [:input {:type "text" :name "email"}]
     [:div
      [:input {:type "submit" :value "Remove"}]]]]
   ])

(defn issue-info [issue]
  (list
   [:style "body { padding: 2em; }td, th { padding: 5px; text-align: left }"]
   [:table {:border 1}
    [:tr
     [:td "Title"]
     [:td (h/h (:issue/title issue))]]
    [:tr
     [:td "Description"]
     [:td (h/h (:issue/description issue))]]
    [:tr
     [:td "Created"]
     [:td (:issue/created-at issue)]]
    [:tr
     [:td "Author"]
     [:td (cust-link (:issue/author issue))]]
    [:tr
     [:td "Voters"]
     [:td (interleave (map (comp cust-link :vote/cust) (:issue/votes issue))
                      (repeat " "))]]]
   [:h4 "Comments"]
   (if (empty? (:issue/comments issue))
     [:p "no comments :("]
     (for [comment (reverse (sort-by :comment/created-at (:issue/comments issue)))]
       [:table {:border 1}
        [:tr
         [:td "Author"]
         [:td (cust-link (:comment/author comment))]]
        [:tr
         [:td "Created"]
         [:td (h/h (:comment/created-at comment))]]
        [:tr
         [:td "Body"]
         [:td (h/h (:comment/body comment))]]]))))
