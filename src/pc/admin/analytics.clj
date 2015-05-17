(ns pc.admin.analytics
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clj-time.core :as time]
            [clojure.string :as str]
            [datomic.api :as d]
            [pc.datomic :as pcd]
            [pc.mixpanel :as mixpanel]
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

(defn mark-early-access []
  (let [db (pcd/default-db)
        early-access-cust-uuids (d/q '{:find [[?uuid ...]]
                                       :where [[?e :flags :flags/requested-early-access]
                                               [?e :cust/uuid ?uuid]]}
                                     db)]
    (doseq [cust-uuid early-access-cust-uuids]
      (mixpanel/engage cust-uuid {:$set {:requested_early_access true}}))))

(defn mark-private-docs []
  (let [db (pcd/default-db)
        early-access-cust-uuids (d/q '{:find [[?uuid ...]]
                                       :where [[?e :flags :flags/private-docs]
                                               [?e :cust/uuid ?uuid]]}
                                     db)]
    (doseq [cust-uuid early-access-cust-uuids]
      (mixpanel/engage cust-uuid {:$set {:has_private_docs true}}))))

(defn mark-duplicates []
  (let [people (fetch-people "boolean(properties[\"$email\"])" :all-pages? true)]
    (doseq [person people
            :let [distinct-id (get person "$distinct_id")]
            :when (re-find #"u'" distinct-id)]
      (mixpanel/engage distinct-id {:$set {:duplicate_user true}}))))

(defn mark-team-trials []
  (let [db (pcd/default-db)
        creator-uuids (d/q '{:find [[?uuid ...]]
                                       :where [[?e :team/creator ?t]
                                               [?t :cust/uuid ?uuid]]}
                                     db)]
    (doseq [cust-uuid creator-uuids]
      (mixpanel/engage cust-uuid {:$set {:send_team_trial_email true}}))))
