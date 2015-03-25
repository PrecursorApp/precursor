(ns frontend.team
  (:require [frontend.db :as db]
            [frontend.sente :as sente]
            [frontend.utils :as utils]))

(defn setup-team-db [app-state]
  (db/setup-listener! (:team-db @app-state)
                      "team-listener"
                      (:comms @app-state)
                      {:team/uuid (get-in @app-state [:team :team/uuid])}
                      :team/transaction
                      (atom {:transactions []
                             :last-undo nil})
                      (:sente @app-state)))

(defn setup [app-state]
  (setup-team-db app-state)
  (sente/subscribe-to-team (:sente @app-state) (get-in @app-state [:team :team/uuid])))
