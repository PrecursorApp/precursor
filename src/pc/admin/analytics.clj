(ns pc.admin.analytics
  (:require [cemerick.url :as url]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clj-time.core :as time]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [pc.datomic :as pcd]
            [pc.http.urls :as urls]
            [pc.mixpanel :as mixpanel]
            [pc.models.cust :as cust-model]
            [pc.models.team :as team-model]
            [pc.util.date :as date-util]
            [pc.util.md5 :as md5]))

(defn sign-mixpanel [args secret]
  (->> args
    (sort-by key)
    (map (fn [[k v]]
           (str (name k) "=" v)))
    (str/join "")
    (#(str %1 secret))
    (md5/encode)))

(defn max-page [{:strs [page page_size total] :as people-result}]
  (dec (Math/ceil (/ total page_size))))

(defn fetch-people* [expression & {:keys [session_id page] :as extra-args}]
  (let [args (merge {:where expression
                     :api_key (mixpanel/api-key)
                     :expire (date-util/timestamp-ms
                              (time/plus (time/now)
                                         (time/hours 1)))}
                    extra-args)]
    (-> (http/get "https://mixpanel.com/api/2.0/engage/"
                  {:throw-exceptions false
                   :query-params (assoc args
                                        :sig (sign-mixpanel args (mixpanel/api-secret)))})
      :body
      json/decode)))

;; https://mixpanel.com/docs/api-documentation/data-export-api#segmentation-expressions
(defn fetch-people [expression & {:keys [all-pages?]}]
  (let [res (fetch-people* expression)
        max-page-num (max-page res)
        session-id (get res "session_id")]
    (if-not all-pages?
      (get res "results")
      (loop [results (get res "results")
             page 0]
        (if (>= page max-page-num)
          results
          (let [new-res (fetch-people* expression :page (inc page) :session_id session-id)]
            (recur (into results (get new-res "results"))
                   (inc page))))))))

(defn fetch-segment [event from-date to-date & {:keys [session_id page] :as extra-args}]
  (let [args (merge {:event event
                     :type "unique"
                     :from_date (mixpanel/->mixpanel-date from-date)
                     :to_date (mixpanel/->mixpanel-date to-date)
                     :api_key (mixpanel/api-key)
                     :expire (date-util/timestamp-ms
                              (time/plus (time/now)
                                         (time/hours 1)))}
                    extra-args)]
    (-> (http/get "https://mixpanel.com/api/2.0/segmentation/"
                  {:throw-exceptions false
                   :query-params (assoc args
                                        :sig (sign-mixpanel args (mixpanel/api-secret)))})
      :body
      json/decode)))

(defn fetch-daily-active-users [event from-date to-date]
  (reduce-kv (fn [m k v]
               (assoc m (clj-time.format/parse k) v))
             {} (get-in (fetch-segment event from-date to-date) ["data" "values" event])))

(defn fetch-monthly-active-users [event from-date to-date]
  (reduce-kv (fn [m k v]
               (assoc m (clj-time.format/parse k) v))
             {} (get-in (fetch-segment event from-date to-date :unit "month") ["data" "values" event])))

(defn pprint-referrers [referrer-string]
  (clojure.pprint/print-table (->> (fetch-people (format "\"%s\" in properties[\"$initial_referrer\"]" referrer-string) :all-pages? true)
                                (map #(-> %
                                        (get-in ["$properties" "$initial_referrer"])
                                        url/url
                                        (assoc :query nil
                                               :protocol "http")
                                        str
                                        str/lower-case))
                                frequencies
                                (sort-by second)
                                reverse
                                (map (fn [[url c]]
                                       {:url (str url) :count c})))))

(defn set-team-urls []
  (let [db (pcd/default-db)
        teams (team-model/all db)]
    (doseq [team teams]
      (Thread/sleep 100)
      (if-not (:team/creator team)
        (do (println "no creator for" (:team/subdomain team))
            (log/infof "no creator for %s" (:team/subdomain team)))
        (do
          (log/infof "setting team props for %s by %s" (:team/subdomain team) (:cust/email (:team/creator team)))
          (mixpanel/engage (:cust/uuid (:team/creator team))
                           {:$ip 0
                            :$ignore_time true
                            :$set {:team_plan_url (urls/team-plan team)
                                   :team_add_users_url (urls/team-add-users team)
                                   :team_intro_doc (urls/from-doc (:team/intro-doc team))}}))))))

#_(defn mark-contacts []
  (let [db (pcd/default-db)
        cust-uuids (map :cust/uuid (cust-model/all db))]
    (doseq [cust-group (partition-all 100 cust-uuids)]
      (Thread/sleep 100)
      (doseq [cust-uuid cust-group
              :let [contact (first (shuffle cust-model/admin-emails))]]
        (log/infof "setting contact to %s for %s" contact cust-uuid)
        (mixpanel/engage cust-uuid {:$ip 0
                                    :$ignore_time true
                                    :$set {:precursor-contact contact}})))))

#_(defn mark-early-access []
  (let [db (pcd/default-db)
        early-access-cust-uuids (d/q '{:find [[?uuid ...]]
                                       :where [[?e :flags :flags/requested-early-access]
                                               [?e :cust/uuid ?uuid]]}
                                     db)]
    (doseq [cust-uuid early-access-cust-uuids]
      (mixpanel/engage cust-uuid {:$set {:requested_early_access true}}))))

#_(defn mark-private-docs []
  (let [db (pcd/default-db)
        early-access-cust-uuids (d/q '{:find [[?uuid ...]]
                                       :where [[?e :flags :flags/private-docs]
                                               [?e :cust/uuid ?uuid]]}
                                     db)]
    (doseq [cust-uuid early-access-cust-uuids]
      (mixpanel/engage cust-uuid {:$set {:has_private_docs true}}))))

#_(defn mark-duplicates []
  (let [people (fetch-people "boolean(properties[\"$email\"])" :all-pages? true)]
    (doseq [person people
            :let [distinct-id (get person "$distinct_id")]
            :when (re-find #"u'" distinct-id)]
      (mixpanel/engage distinct-id {:$set {:duplicate_user true}}))))

#_(defn mark-team-trials []
  (let [db (pcd/default-db)
        creator-uuids (d/q '{:find [[?uuid ...]]
                                       :where [[?e :team/creator ?t]
                                               [?t :cust/uuid ?uuid]]}
                                     db)]
    (doseq [cust-uuid creator-uuids]
      (mixpanel/engage cust-uuid {:$set {:send_team_trial_email true}}))))
