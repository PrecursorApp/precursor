(ns pc.models.access-grant
  (:require [pc.datomic :as pcd]
            [pc.datomic.web-peer :as web-peer]
            [clj-time.core :as time]
            [clj-time.coerce]
            [crypto.random]
            [pc.datomic.web-peer :as web-peer]
            [datomic.api :refer [db q] :as d]))

(defn read-api [grant]
  (-> grant
    (select-keys [:access-grant/document
                  :access-grant/email
                  :access-grant/expiry
                  :access-grant/grant-date])
    (assoc :db/id (web-peer/client-id grant))))

(defn find-by-document [db doc]
  (->> (d/q '{:find [?t]
              :in [$ ?doc-id]
              :where [[?t :access-grant/document ?doc-id]]}
            db (:db/id doc))
    (map first)
    (map #(d/entity db %))))

(defn find-by-token [db token]
  (->> (d/q '{:find [?t]
              :in [$ ?token]
              :where [[?t :access-grant/token ?token]]}
            db token)
    ffirst
    (d/entity db)))

(defn grant-access [doc email granter annotations]
  (let [txid (d/tempid :db.part/tx)
        token (crypto.random/url-part 32)
        grant-date (java.util.Date.)
        expiry (clj-time.coerce/to-date (time/plus (clj-time.coerce/from-date grant-date) (time/weeks 2)))
        temp-id (d/tempid :db.part/user)]
    @(d/transact (pcd/conn)
                 [(assoc annotations :db/id txid)
                  (web-peer/server-frontend-id temp-id (:db/id doc))
                  {:db/id temp-id
                   :access-grant/document (:db/id doc)
                   :access-grant/email email
                   :access-grant/token token
                   :access-grant/expiry expiry
                   :access-grant/grant-date grant-date
                   :access-grant/granter (:db/id granter)
                   :needs-email :email/access-grant-created
                   :access-grant/doc-email (str (:db/id doc) "-" email)}])))

(defn get-granter [db access-grant]
  (d/entity db (:access-grant/granter access-grant)))
