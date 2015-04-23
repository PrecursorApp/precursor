(ns pc.http.team
  (:require [clj-time.core :as time]
            [clojure.java.io :as io]
            [clojure.tools.reader.edn :as edn]
            [datomic.api :as d]
            [pc.datomic :as pcd]
            [pc.datomic.web-peer :as web-peer]
            [pc.models.doc :as doc-model]
            [pc.models.permission :as permission-model]
            [pc.models.plan :as plan-model]
            [pc.models.team :as team-model]
            [slingshot.slingshot :refer (try+ throw+)])
  (:import [java.io PushbackReader]
           [java.util UUID]))

(defn read-doc* [doc]
  (edn/read (PushbackReader. (io/reader (io/resource (format "docs/%s.edn" doc))))))

(def read-doc (memoize read-doc*))

(defn create-initial-team-entities
  "Creates the landing doc that we direct people to for their first doc and the team's plan"
  [team coupon-code]
  (let [ ;; don't assign the doc to anything anything yet, it may get
        ;; unused by the cas
        doc (doc-model/create! {:document/name (format "Welcome to the %s team on Precursor"
                                                       (:team/subdomain team))
                                :document/privacy :document.privacy/private})
        planid (d/tempid :db.part/user)]
    @(d/transact (pcd/conn) (conj
                             (map-indexed
                              (fn [i l]
                                (assoc l
                                       :db/id (d/tempid :db.part/user)
                                       :layer/document (:db/id doc)
                                       :frontend/id (UUID. (:db/id doc) (inc i))))
                              (read-doc "team-intro"))
                             {:db/id (d/tempid :db.part/tx)
                              :transaction/document (:db/id doc)
                              :transaction/broadcast true}

                             {:db/id (:db/id team)
                              :team/intro-doc (:db/id doc)
                              :team/plan (merge
                                          {:db/id planid
                                           :plan/trial-end (clj-time.coerce/to-date
                                                            (time/plus (time/now)
                                                                       (time/days 14)))}
                                          (when coupon-code
                                            {:coupon-code coupon-code}))}

                             {:db/id (:db/id doc)
                              :document/team (:db/id team)}

                             (web-peer/server-frontend-id planid (:db/id team))))))

(defn add-first-cust [team cust]
  (permission-model/grant-first-team-permit team cust :permission.permits/admin))

(defn setup-new-team
  "Creates a new team given a subdomain. Returns the team"
  [subdomain cust coupon-code]
  (try+
   (let [team (-> (team-model/create-for-subdomain! subdomain cust {:cust/uuid (:cust/uuid cust)})
                :db-after
                (team-model/find-by-subdomain subdomain))
         coupon-code (when (and coupon-code (plan-model/coupon-exists? (pcd/default-db) coupon-code))
                       coupon-code)]
     (add-first-cust team cust)
     (-> (create-initial-team-entities team coupon-code)
       :db-after
       (team-model/find-by-subdomain subdomain)))
   (catch :db/error t
     (if (= :db.error/unique-conflict (:db/error t))
       (throw+ {:status 400 :public-message "Subdomain is already in use"
                :error :subdomain-exists})
       (throw+)))))
