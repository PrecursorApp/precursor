(ns frontend.models.repo
  (:require [clojure.set :as set]
            [frontend.datetime :as datetime]
            [goog.string :as gstring]
            goog.string.format))

(defn can-follow? [repo]
  (and (not (:following repo))
       (or (:admin repo)
           (:has_followers repo))))

(defn should-do-first-follower-build? [repo]
  (and (not (:following repo))
       (:admin repo)
       (not (:has_followers repo))))

(defn requires-invite? [repo]
  (and (not (:following repo))
       (not (:admin repo))
       (not (:has_followers repo))))

(defn id [repo]
  (:vcs_url repo))
