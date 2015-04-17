(ns frontend.models.plan
  (:require [cljs-time.core :as time]
            [cljs-time.coerce]))

(defn in-trial? [plan]
  (if (:plan/trial-end plan)
    (time/after? (cljs-time.coerce/from-date (:plan/trial-end plan)) (time/now))))
