(ns pc.early-access
  (:require [datomic.api :as d]
            [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [pc.datomic :as pcd]
            [pc.models.cust :as cust-model]
            [pc.models.flag :as flag-model]
            [pc.profile :as profile]
            [org.httpkit.client :as http]))

(defn create-request [cust data]
  @(d/transact (pcd/conn) [(flag-model/add-flag-tx cust :flags/requested-early-access)
                           {:db/id (d/tempid :db.part/user)
                            :cust/uuid (:cust/uuid cust)
                            :early-access-request/company-name (or (:company-name data) "Not provided")
                            :early-access-request/employee-count (or (:employee-count data) "Not provided")
                            :early-access-request/use-case (or (:use-case data) "Not provided")}]))

(defn approve-request [cust]
  @(d/transact (pcd/conn) [(flag-model/add-flag-tx cust :flags/private-docs)
                           [:db/add (:db/id cust) :needs-email :email/early-access-granted]]))

(defn find-by-cust [db cust]
  (map (partial d/entity db)
       (d/q '{:find [[?t ...]]
              :in [$ ?cust-uuid]
              :where [[?t :cust/uuid ?cust-uuid]
                      [?t :early-access-request/company-name]]}
            db (:cust/uuid cust))))

(defn handle-early-access-requests [transaction]
  (let [db (:db-after transaction)
        datoms (:tx-data transaction)
        early-access-eid (d/entid db :flags/requested-early-access)]
    (when-let [datom (first (filter #(= early-access-eid (:v %)) datoms))]
      (let [slack-url (profile/slack-customer-ping-url)
            cust (some->> datom :e (#(d/datoms db :eavt % :cust/uuid)) first :v (cust-model/find-by-uuid db))
            username (:cust/email cust "ping-bot")
            icon_url (str (:google-account/avatar cust))
            early-access-request (first (find-by-cust db cust))]
        (let [message (format "%s requested early access\nCompany name: %s\nEmployee count: %s\nUse case: %s"
                              (:cust/email cust)
                              (:early-access-request/company-name early-access-request)
                              (:early-access-request/employee-count early-access-request)
                              (:early-access-request/use-case early-access-request))]
          (http/post slack-url {:form-params {"payload" (json/encode {:text message :username username :icon_url icon_url})}}))))))
