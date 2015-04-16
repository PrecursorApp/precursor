(ns frontend.models.team
  (:require [datascript :as d]
            [frontend.datascript :as ds]))

(defn find-by-subdomain [db subdomain]
  (d/entity db (d/q '{:find [?t .]
                      :in [$ ?s]
                      :where [[?t :team/subdomain ?s]]}
                    db subdomain)))

(defn find-by-uuid [db uuid]
  (d/entity db (d/q '{:find [?t .]
                      :in [$ ?u]
                      :where [[?t :team/uuid ?u]]}
                    db uuid)))
