(ns pc.http.issues
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [pc.datomic :as pcd]
            [pc.http.datomic2 :as datomic2]
            [pc.models.cust :as cust-model]
            [pc.models.issue :as issue-model]
            [pc.http.sente.common :as sente-common]
            [pc.http.datomic2 :as datomic2])
  (:import [java.util UUID]))

(defonce issue-subs (atom #{}))

(defn subscribe [{:keys [client-id ?data ?reply-fn] :as req}]
  (swap! issue-subs conj client-id)
  (let [uncompleted-issues (map issue-model/read-api (issue-model/uncompleted-issues (:db req)))]
    (sente-common/send-reply req {:entities uncompleted-issues
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

(defn relax-q [q]
  (str/join " OR "
            (for [q-piece (str/split q #"\s+")]
              (str q-piece "*"))))

(defn safe-q [q]
  (str/trim (str/replace q #"[^A-Za-z ]+" "")))

(defn search [{:keys [client-id ?data ?reply-fn] :as req}]
  (let [safed-q (safe-q (:q ?data))]
    (if (seq safed-q)
      (let [relaxed-q (relax-q safed-q)
            db (:db req)
            results (concat (map (partial issue-model/issue-search-read-api db)
                                 (issue-model/search-issues db relaxed-q))
                            (map (partial issue-model/comment-search-read-api db)
                                 (issue-model/search-comments db relaxed-q)))]
        (sente-common/send-reply req
                                 {:results results
                                  :q (:q ?data)}))
      (sente-common/send-reply req
                               {:results []
                                :q (:q ?data)}))))

(defn fetch [{:keys [client-id ?data ?reply-fn] :as req}]
  (let [frontend-id (:frontend/issue-id ?data)]
    (log/infof "fetching issue %s for %s" frontend-id client-id)
    (sente-common/send-msg req client-id [:issue/db-entities {:entities [(issue-model/read-api (issue-model/find-by-frontend-id (:db req) frontend-id))]}])))

(defn fetch-completed [{:keys [client-id ?data ?reply-fn] :as req}]
  (let [completed-issues (map issue-model/read-api (issue-model/completed-issues (:db req)))]
    (sente-common/send-msg req client-id [:issue/db-entities {:entities completed-issues
                                                              :entity-type :issue}])))
