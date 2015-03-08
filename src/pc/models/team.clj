(ns pc.models.team
  (:require [datomic.api :as d]
            [pc.datomic :as pcd]))

;; We'll pretend we have a type here
#_(t/def-alias Team (HMap :mandatory {:team/subdomain String
                                      :db/id Long
                                      ;; probably a uuid type
                                      :team/uuid String}))

(defn find-by-subdomain [db subdomain]
  (->> subdomain
    (d/datoms db :avet :team/subdomain)
    first
    :e
    (d/entity db)))
