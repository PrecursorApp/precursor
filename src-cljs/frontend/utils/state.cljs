(ns frontend.utils.state
  (:require [frontend.state :as state]
            [frontend.utils.vcs-url :as vcs-url]
            [frontend.utils.seq :refer [find-index]]))

(defn set-dashboard-crumbs [state {:keys [org repo branch]}]
  (assoc-in state state/crumbs-path (vec (concat
                                          (when org [{:type :org
                                                      :username org}])
                                          (when repo [{:type :project
                                                       :username org :project repo}])
                                          (when branch [{:type :project-branch
                                                         :username org :project repo :branch branch}])))))

(defn reset-current-build [state]
  (assoc state :current-build-data {:build nil
                                    :usage-queue-data {:builds nil
                                                       :show-usage-queue false}
                                    :artifact-data {:artifacts nil
                                                    :show-artifacts false}
                                    :current-container-id 0
                                    :container-data {:current-container-id 0
                                                     :containers nil}}))

(defn reset-current-project [state]
  (assoc state :current-project-data {:project nil
                                      :plan nil
                                      :settings {}
                                      :tokens nil
                                      :checkout-keys nil
                                      :envvars nil}))

(defn reset-current-org [state]
  (assoc state :current-org-data {:current-org-data {:plan nil
                                                     :projects nil
                                                     :users nil
                                                     :name nil}}))

(defn stale-current-project? [state project-name]
  (and (get-in state state/project-path)
       (not= project-name (vcs-url/project-name (get-in state (conj state/project-path :vcs_url))))))

(defn stale-current-org? [state org-name]
  (and (get-in state state/org-name-path)
       (not= org-name (get-in state state/org-name-path))))

(defn find-repo-index
  "Path for a given repo. Login is the username, type is user or org, name is the repo name."
   [state login type repo-name]
   (when-let [repos (get-in state (state/repos-path login type))]
     (find-index #(= repo-name (:name %)) repos)))

(defn clear-page-state [state]
  (-> state
      (assoc :crumbs nil)
      (assoc-in state/inputs-path nil)
      (assoc-in state/error-message-path nil)
      (assoc-in state/page-scopes-path nil)
      (assoc-in state/user-options-shown-path false)))

(defn merge-inputs [defaults inputs keys]
  (merge (select-keys defaults keys)
         (select-keys inputs keys)))
