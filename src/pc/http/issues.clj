(ns pc.http.issues
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d]
            [pc.datomic :as pcd]
            [pc.models.cust :as cust-model]
            [pc.models.issue :as issue-model]
            [pc.http.sente.common :as sente-common]
            [pc.http.datomic2 :as datomic2])
  (:import [java.util UUID]))

(defonce issue-subs (atom #{}))

(defn subscribe [{:keys [client-id ?data ?reply-fn] :as req}]
  (swap! issue-subs conj client-id)
  (let [issues (map issue-model/read-api (issue-model/all-issues (:db req)))]
    (sente-common/send-reply req {:entities issues
                                  :entity-type :issue})))

(defn unsubscribe [client-id]
  (swap! issue-subs disj client-id))

(defn handle-transaction [{:keys [client-id ?data ?reply-fn] :as req}]
  (if-let [cust (some-> req :ring-req :auth :cust)]
    (let [datoms (->> ?data
                   :datoms
                   (remove (comp nil? :v)))
          _ (def myd datoms)
          ;; note that these aren't all of the rejected datoms, just the ones not on the whitelist
          rejects (remove (comp datomic2/issue-whitelisted?
                                pcd/datom->transaction)
                          datoms)]
      (log/infof "transacting %s datoms (minus %s rejects) on %s"
                 (count datoms) (count rejects) client-id)
      (datomic2/transact-issue! datoms
                                {:client-id client-id
                                 :cust cust
                                 :session-uuid (UUID/fromString (get-in req [:ring-req :session :sente-id]))
                                 :timestamp (:receive-instant req)})
      (sente-common/send-reply req {:rejected-datoms rejects}))
    (comment "Handle logged out users")))

(defn set-status [{:keys [client-id ?data ?reply-fn] :as req}]
  (let [cust (some-> req :ring-req :auth :cust)]
    (when (contains? cust-model/admin-emails (:cust/email cust))
      (let [issue-uuid (:frontend/issue-id ?data)
            status (:issue/status ?data)]
        (assert (= "issue.status" (namespace status)))
        @(d/transact (pcd/conn) [{:db/id (d/tempid :db.part/tx)
                                  :transaction/broadcast true
                                  :transaction/issue-tx? true
                                  :cust/uuid (:cust/uuid cust)}
                                 {:frontend/issue-id issue-uuid
                                  :db/id (d/tempid :db.part/user)
                                  :issue/status status}])))))
