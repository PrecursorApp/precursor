(ns frontend.models.container
  (:require [clojure.set :refer (intersection)]
            [frontend.datetime :as datetime]
            [goog.string :as gstring]
            goog.string.format))

(defn id [container]
  (:index container))

(defn status [container build-running?]
  (let [action-statuses (->> container :actions (remove :filler-action) (map :status) (remove nil?) set)]
    (cond
     ;; If there are no action statuses, or the last one is running, it's 'running'.
     (or (empty? action-statuses)
         (= "running" (last action-statuses)))
     :running
     ;; If it has any of the failure-like statuses, it's 'failed'.
     (some action-statuses ["failed" "timedout" "cancelled" "infrastructure_fail"])
     :failed
     ;; If any of the actions have been canceled, it's 'canceled'.
     (some :canceled (:actions container))
     :canceled
     ;; If there's only one status, and it's "success", it's 'success'.
     (and (= action-statuses #{"success"}))
     (if build-running? :waiting :success))))

(defn status->classes [status]
  (some-> status name (cons nil)))
