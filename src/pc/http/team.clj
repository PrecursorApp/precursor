(ns pc.http.team
  (:require [clojure.java.io :as io]
            [clojure.tools.reader.edn :as edn]
            [datomic.api :as d]
            [pc.datomic :as pcd]
            [pc.models.doc :as doc-model]
            [pc.models.permission :as permission-model]
            [pc.models.team :as team-model]
            [slingshot.slingshot :refer (try+ throw+)])
  (:import [java.io PushbackReader]
           [java.util UUID]))

(defn read-doc* [doc]
  (edn/read (PushbackReader. (io/reader (io/resource (format "docs/%s.edn" doc))))))

(def read-doc (memoize read-doc*))

(defn create-landing-doc
  "Creates the landing doc that we direct people to for their first doc"
  [team]
  (let [;; don't assign the doc to anything anything yet, it may get
        ;; unused by the cas
        doc (doc-model/create! {:document/name (format "Welcome to the %s team on Precursor"
                                                       (:team/subdomain team))
                                :document/privacy :document.privacy/private})]
    ;; This should always succeed, so we'll just let Rollbar catch any cas errors
    @(d/transact (pcd/conn) [[:db.fn/cas (:db/id team) :team/intro-doc nil (:db/id doc)]])
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
                             {:db/id (:db/id doc)
                              :document/team (:db/id team)}))))

(defn add-first-cust [team cust]
  (permission-model/grant-first-team-permit team cust :permission.permits/admin))

(defn setup-new-team
  "Creates a new team given a subdomain. Returns the team"
  [subdomain cust]
  (try+
   (let [team (-> (team-model/create-for-subdomain! subdomain {})
                :db-after
                (team-model/find-by-subdomain subdomain))]
     (add-first-cust team cust)
     (-> (create-landing-doc team)
       :db-after
       (team-model/find-by-subdomain subdomain)))
   (catch :db/error t
     (if (= :db.error/unique-conflict (:db/error t))
       (throw+ {:status 400 :public-message "Subdomain is already in use"})
       (throw+)))))
