(ns pc.http.issues
  (:require [clojure.tools.logging :as log]
            [pc.datomic :as pcd]
            [pc.models.issue :as issue-model]
            [pc.http.datomic2 :as datomic2])
  (:import [java.util UUID]))

(defonce issue-subs (atom #{}))

(defn subscribe [{:keys [client-id ?data ?reply-fn] :as req}]
  (swap! issue-subs conj client-id)
  (let [issues (map issue-model/read-api (issue-model/all-issues (:db req)))]
    (?reply-fn {:entities issues
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
      (when ?reply-fn
        (?reply-fn {:rejected-datoms rejects})))
    (comment "Handle logged out users")))
