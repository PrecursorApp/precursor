(ns frontend.api
  (:require [frontend.models.user :as user-model]
            [frontend.models.build :as build-model]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.ajax :as ajax]
            [frontend.utils.vcs-url :as vcs-url]
            [goog.string :as gstring]
            [goog.string.format]
            [secretary.core :as sec]))

(defn get-projects [api-ch]
  (ajax/ajax :get "/api/v1/projects" :projects api-ch))

(defn get-usage-queue [build api-ch]
  (ajax/ajax :get
             (gstring/format "/api/v1/project/%s/%s/%s/usage-queue"
                             (vcs-url/org-name (:vcs_url build))
                             (vcs-url/repo-name (:vcs_url build))
                             (:build_num build))
             :usage-queue
             api-ch
             :context (build-model/id build)))

(defn dashboard-builds-url [{:keys [branch repo org admin deployments query-params builds-per-page]}]
  (let [url (cond admin "/api/v1/admin/recent-builds"
                  deployments "/api/v1/admin/deployments"
                  branch (gstring/format "/api/v1/project/%s/%s/tree/%s" org repo branch)
                  repo (gstring/format "/api/v1/project/%s/%s" org repo)
                  org (gstring/format "/api/v1/organization/%s" org)
                  :else "/api/v1/recent-builds")
        page (get query-params :page 0)]
    (str url "?" (sec/encode-query-params (merge {:shallow true
                                                  :offset (* page builds-per-page)
                                                  :limit builds-per-page}
                                                 query-params)))))

(defn get-dashboard-builds [{:keys [branch repo org admin query-params builds-per-page] :as args} api-ch]
  (let [url (dashboard-builds-url args)]
    (ajax/ajax :get url :recent-builds api-ch :context {:branch branch :repo repo :org org})))

(defn get-action-output [{:keys [vcs-url build-num step index output-url]
                          :as args} api-ch]
  (let [url (or output-url
                (gstring/format "/api/v1/project/%s/%s/output/%s/%s"
                                (vcs-url/project-name vcs-url)
                                build-num
                                step
                                index))]
    (ajax/ajax :get
               url
               :action-log
               api-ch
               :context args)))

(defn get-project-settings [project-name api-ch]
  (ajax/ajax :get (gstring/format "/api/v1/project/%s/settings" project-name) :project-settings api-ch :context {:project-name project-name}))
