(ns pc.models.admin
  (:require [clojure.tools.logging :as log]
            [pc.datomic.admin-db :as admin-db]
            [pc.datomic.schema :as schema]
            [pc.models.flag :as flag-model]
            [datomic.api :refer [db q] :as d]))

(defn find-by-google-sub [db google-sub]
  (d/entity db (d/q '{:find [?e .]
                      :in [$ ?sub]
                      :where [[?e :google-account/sub ?sub]]}
                    db google-sub)))

(defn find-by-http-session-key [db http-session-key]
  (d/entity db (d/q '{:find [?e .] :in [$ ?key]
                      :where [[?e :admin/http-session-key ?key]]}
                    db http-session-key)))

(defn update-session-key! [admin key]
  (-> @(d/transact (admin-db/admin-conn) [{:db/id (:db/id admin)
                                           :admin/http-session-key key}])
    :db-after
    (d/entity (:db/id admin))))
