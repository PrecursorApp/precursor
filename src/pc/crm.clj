(ns pc.crm
  (:require [clj-http.client :as http]
            [clj-time.core :as time]
            [clj-time.coerce]
            [cheshire.core :as json]
            [clojure.string :as str]
            [datomic.api :as d]
            [pc.datomic :as pcd]
            [pc.http.admin.urls :as admin-urls]
            [pc.models.cust :as cust-model]
            [pc.profile :as profile]
            [pc.utils :as utils]))

(defn search-dribbble [query]
  (-> (http/get "https://www.googleapis.com/customsearch/v1"
                {:query-params {:key (profile/google-api-key)
                                :cx (profile/dribbble-custom-search-id)
                                :q query}})
    :body
    json/decode
    (get "items")))

(def dribbble-user-link-re #"^https://dribbble\.com/([^/]+)$")
(defn find-dribbble-username [cust]
  (when (:cust/last-name cust)
    (some->> (search-dribbble (str (:cust/first-name cust) " " (:cust/last-name cust)))
      (map #(get % "link"))
      (filter #(re-find dribbble-user-link-re %))
      first
      (re-find dribbble-user-link-re)
      last)))

(defn update-with-dribbble-username [cust]
  (if-let [username (utils/with-report-exceptions (find-dribbble-username cust))]
    (cust-model/update! cust {:cust/guessed-dribbble-username username})
    cust))

;; TODO: reset this and put it somewhere more secure if we use dribbble for sign in
(def dribbble-token "2efdfd2b34045390c44368daab5e94ec3f9280185cfee1dfbc2d4aa0b27c1e9c")
(defn get-dribbble-profile [username]
  (-> (http/get (format "https://api.dribbble.com/v1/users/%s" username)
                {:insecure? true
                 :query-params {:access_token dribbble-token}})
    :body
    json/decode))

(defn ping-chat-with-new-user [cust]
  (utils/with-report-exceptions
    (let [db (pcd/default-db)
          create-instant (cust-model/created-at db cust)
          cust-count (cust-model/cust-count (d/as-of db create-instant))
          midnight-pt (clj-time.coerce/to-date
                       (time/with-time-at-start-of-day
                         (time/now)
                         (time/time-zone-for-id "America/Los_Angeles")))
          cust-count-yesterday (cust-model/cust-count (d/as-of db midnight-pt))
          dribbble-profile (some-> cust :cust/guessed-dribbble-username get-dribbble-profile)
          cust-name (str/trim (str (:cust/first-name cust) " " (:cust/last-name cust)))
          message (str (format "New user (#%s, #%s today): <https://plus.google.com/%s|%s> <%s|%s>\nOccupation: %s"
                               cust-count
                               (- cust-count cust-count-yesterday)
                               (:google-account/sub cust)
                               cust-name
                               (admin-urls/cust-info-from-cust cust)
                               (:cust/email cust)
                               (or (:cust/occupation cust) "Unknown"))
                       (when dribbble-profile
                         (format "\nDribbble: <%s|%s> %s followers "
                                 (get dribbble-profile "html_url")
                                 (get dribbble-profile "username")
                                 (get dribbble-profile "followers_count"))))]
      (http/post (profile/slack-customer-ping-url)
                 {:form-params {"payload" (json/encode {:text message
                                                        :username (:cust/email cust)
                                                        :icon_url (str (:google-account/avatar cust))})}}))))
