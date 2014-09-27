(ns frontend.utils.vcs-url
  (:require [clojure.string :as string]))

(defn project-name [vcs-url]
  (last (re-find #"^https?://[^/]+/(.*)" vcs-url)))

;; slashes aren't allowed in github org/user names or project names
(defn org-name [vcs-url]
  (first (string/split (project-name vcs-url) #"/")))

(defn repo-name [vcs-url]
  (second (string/split (project-name vcs-url) #"/")))

(defn display-name [vcs-url]
  (.replace (project-name vcs-url) "/" \u200b))

(defn project-path [vcs-url]
  (str "/gh/" (project-name vcs-url)))
