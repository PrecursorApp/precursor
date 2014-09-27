(ns frontend.models.plan
  (:require [frontend.utils :as utils :include-macros true]
            [goog.string :as gstring]
            [cljs-time.core :as time]
            [cljs-time.format :as time-format]))

(defn max-parallelism
  "Maximum parallelism that the plan allows (usually 16x)"
  [plan]
  (get-in plan [:template_properties :max_parallelism]))

(defn usable-containers
  "Maximum containers that the plan has available to it"
  [plan]
  (max (:containers_override plan) (:containers plan) (get-in plan [:template_properties :free_containers])))

(defn max-selectable-parallelism [plan]
  (min (max-parallelism plan)
       (usable-containers plan)))


(defn piggieback? [plan org-name]
  (not= (:org_name plan) org-name))

(defn paid? [plan]
  (not= (get-in plan [:template_properties :type] "trial") "trial"))

(defn can-edit-plan? [plan org-name]
  (and (paid? plan) (not (piggieback? plan org-name))))

(defn trial? [plan]
  (some-> plan :template_properties :type name (= "trial")))

(defn trial-over? [plan]
  (time/after? (time/now) (time-format/parse (:trial_end plan))))

;; true  if the plan has an active Stripe discount coupon.
;; false if the plan is nil (not loaded yet) or has no discount applied
(defn has-active-discount? [plan]
  (get-in plan [:discount :coupon :valid]))

(defn days-left-in-trial
  "Returns number of days left in trial, can be negative."
  [plan]
  (let [trial-end (time-format/parse (:trial_end plan))
        now (time/now)]
    (if (time/after? trial-end now)
      ;; count partial days as a full day
      (inc (time/in-days (time/interval now trial-end)))
      (- (time/in-days (time/interval trial-end now))))))

(defn pretty-trial-time [plan]
  (let [trial-interval (time/interval (time/now) (time-format/parse (:trial_end plan)))
        hours-left (time/in-hours trial-interval)]
    (cond (< 24 hours-left)
          (str (days-left-in-trial plan) " days")

          (< 1 hours-left)
          (str hours-left " hours")

          :else
          (str (time/in-minutes trial-interval) " minutes"))))

;; The template tells how to price the plan
(def default-template-properties {:price 19 :container_cost 50 :id "p18" :max_containers 1000 :free_containers 1})

(defn container-cost [template-properties containers]
  (let [{:keys [free_containers container_cost]} template-properties]
    (max 0 (* container_cost (- containers free_containers)))))

(defn cost [template-properties containers]
  (+ (:price template-properties)
     (container-cost template-properties containers)))

(defn stripe-cost
  "Normalizes the Stripe amount on the plan to dollars."
  [plan]
  (/ (:amount plan) 100))

(defn grandfathered? [plan]
  (< (stripe-cost plan)
     (cost (:template-properties plan) (:containers plan))))
