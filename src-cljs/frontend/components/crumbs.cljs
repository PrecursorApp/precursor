(ns frontend.components.crumbs
  (:require [frontend.routes :as routes]
            [frontend.utils :as utils :include-macros true]
            [om.core :as om])
  (:require-macros [frontend.utils :refer [html]]))

(defn crumb-node [{:keys [active name path]}]
  (if active
    [:a {:disabled true :title name} name " "]
    [:a {:href path :title name} name " "]))

(defmulti render-crumb
  (fn [{:keys [type]}] type))

(defmethod render-crumb :default
  [attrs]
  (crumb-node attrs))

(defmethod render-crumb :project
  [{:keys [username project active]}]
  (crumb-node {:name project
               :path (routes/v1-dashboard-path {:org username :repo project})
               :active active}))

(defmethod render-crumb :project-settings
  [{:keys [username project active]}]
  (crumb-node {:name "project settings"
               :path (routes/v1-project-settings {:org username :repo project})
               :active active}))

(defmethod render-crumb :project-branch
  [{:keys [username project branch active]}]
  (crumb-node {:name (if branch
                       (utils/trim-middle (utils/display-branch branch) 45)
                       "...")
               :path (routes/v1-dashboard-path {:org username
                                                :repo project
                                                :branch branch})
               :active active}))

(defmethod render-crumb :build
  [{:keys [username project build-num active]}]
  (crumb-node {:name (str "build " build-num)
               :path (routes/v1-build-path username project build-num)
               :active active}))

(defmethod render-crumb :org
  [{:keys [username active]}]
  (crumb-node {:name username
               :path (routes/v1-dashboard-path {:org username})
               :active active}))

(defmethod render-crumb :org-settings
  [{:keys [username active]}]
  (crumb-node {:name "organization settings"
               :path (routes/v1-org-settings {:org username})
               :active active}))

(defn crumbs [crumbs-data]
  (map render-crumb crumbs-data))
