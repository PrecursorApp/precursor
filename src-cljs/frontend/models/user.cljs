(ns frontend.models.user
  (:require [clojure.set :as set]
            [frontend.datetime :as datetime]
            [goog.string :as gstring]
            goog.string.format))

(defn missing-scopes [user]
  (let [current-scopes (set (:github_oauth_scopes user))]
    (set/union (when (empty? (set/intersection current-scopes #{"user" "user:email"}))
                 #{"user:email"})
               (when-not (contains? current-scopes "repo")
                 #{"repo"}))))

(defn public-key-scope? [user]
  (some #{"admin:public_key"} (:github_oauth_scopes user)))
