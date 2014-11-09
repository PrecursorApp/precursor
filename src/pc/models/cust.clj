(ns pc.models.cust
  "cust is what would usually be called user, we call it cust b/c
   Clojure has already taken the name user in the repl"
  (:require [pc.datomic :as pcd]
            [datomic.api :refer [db q] :as d]))


;; We'll pretend we have a type here
#_(t/def-alias Cust (HMap :mandatory {:cust/email String
                                      :db/id Long
                                      ;; probably a uuid type
                                      :cust/uuid String
                                      :google-account/sub String}
                          :optional {:cust/name String
                                     ;; probably a uuid type
                                     :cust/http-sesion-key String}))

(defn find-by-google-sub [db google-sub]
  (pcd/touch-one '{:find [?e] :in [$ ?sub]
                   :where [[?e :google-account/sub ?sub]]}
                 db google-sub))

;; TODO: maybe these should return an entity instead of touching?
(defn create! [cust-attrs]
  (let [temp-id (d/tempid :db.part/user)
        {:keys [tempids db-after]} @(d/transact (pcd/conn)
                                                [(assoc cust-attrs :db/id temp-id)])]
    (->> (d/resolve-tempid db-after
                           tempids
                           temp-id)
         (d/entity db-after)
         pcd/touch+)))

(defn update! [cust new-attrs]
  (let [{:keys [db-after]} @(d/transact (pcd/conn) (map (fn [[a v]]
                                                          [:db/add (:db/id cust) a v])
                                                        new-attrs))]
    (pcd/touch+ (d/entity db-after (:db/id cust)))))
