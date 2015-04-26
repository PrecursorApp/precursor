(ns frontend.models.plan
  (:require [cljs-time.core :as time]
            [cljs-time.coerce]))

(defn in-trial? [plan]
  (if (:plan/trial-end plan)
    (time/after? (cljs-time.coerce/from-date (:plan/trial-end plan)) (time/now))))

(defn active-discount? [plan]
  (and (:discount/coupon plan)
       (time/after? (cljs-time.coerce/from-date (:discount/end plan))
                    (time/now))))

(defn cost
  "Calculates plan cost in cents"
  [plan]
  (let [discount-pct (if (active-discount? plan)
                       (-> plan :discount/coupon :coupon/percent-off (/ 100))
                       0)
        base-cost (* (count (:plan/active-custs plan))
                     1000)]
    (max 1000 (* base-cost (- 1 discount-pct)))))
