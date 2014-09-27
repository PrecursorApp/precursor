(ns frontend.controllers.api
  (:require [cljs.core.async :refer [close!]]
            [frontend.api :as api]
            [frontend.async :refer [put!]]
            [frontend.models.action :as action-model]
            [frontend.models.build :as build-model]
            [frontend.models.project :as project-model]
            [frontend.models.repo :as repo-model]
            [frontend.pusher :as pusher]
            [frontend.routes :as routes]
            [frontend.state :as state]
            [frontend.favicon]
            [frontend.utils.ajax :as ajax]
            [frontend.utils.state :as state-utils]
            [frontend.utils.vcs-url :as vcs-url]
            [frontend.utils :as utils :refer [mlog merror]]
            [goog.string :as gstring])
  (:require-macros [frontend.utils :refer [inspect]]))

;; when a button is clicked, the post-controls will make the API call, and the
;; result will be pushed into the api-channel
;; the api controller will do assoc-in
;; the api-post-controller can do any other actions

;; --- API Multimethod Declarations ---

(defmulti api-event
  ;; target is the DOM node at the top level for the app
  ;; message is the dispatch method (1st arg in the channel vector)
  ;; args is the 2nd value in the channel vector)
  ;; state is current state of the app
  ;; return value is the new state
  (fn [target message status args state] [message status]))

(defmulti post-api-event!
  (fn [target message status args previous-state current-state] [message status]))

;; --- API Multimethod Implementations ---

(defmethod api-event :default
  [target message status args state]
  ;; subdispatching for state defaults
  (let [submethod (get-method api-event [:default status])]
    (if submethod
      (submethod target message status args state)
      (do (merror "Unknown api: " message args)
          state))))

(defmethod post-api-event! :default
  [target message status args previous-state current-state]
  ;; subdispatching for state defaults
  (let [submethod (get-method post-api-event! [:default status])]
    (if submethod
      (submethod target message status args previous-state current-state)
      (merror "Unknown api: " message status args))))

(defmethod api-event [:default :started]
  [target message status args state]
  (mlog "No api for" [message status])
  state)

(defmethod post-api-event! [:default :started]
  [target message status args previous-state current-state]
  (mlog "No post-api for: " [message status]))

(defmethod api-event [:default :success]
  [target message status args state]
  (mlog "No api for" [message status])
  state)

(defmethod post-api-event! [:default :success]
  [target message status args previous-state current-state]
  (mlog "No post-api for: " [message status]))

(defmethod api-event [:default :failed]
  [target message status args state]
  (mlog "No api for" [message status])
  state)

(defmethod post-api-event! [:default :failed]
  [target message status args previous-state current-state]
  (put! (get-in current-state [:comms :errors]) [:api-error args])
  (mlog "No post-api for: " [message status]))

(defmethod api-event [:default :finished]
  [target message status args state]
  (mlog "No api for" [message status])
  state)

(defmethod post-api-event! [:default :finished]
  [target message status args previous-state current-state]
  (mlog "No post-api for: " [message status]))


(defmethod api-event [:projects :success]
  [target message status args state]
  (mlog "projects success")
  (assoc-in state [:projects] (:resp args)))

(defmethod api-event [:me :success]
  [target message status args state]
  (update-in state state/user-path merge (:resp args)))

(defmethod api-event [:recent-builds :success]
  [target message status args state]
  (if-not (and (= (get-in state [:navigation-data :org])
                  (get-in args [:context :org]))
               (= (get-in state [:navigation-data :repo])
                  (get-in args [:context :repo]))
               (= (get-in state [:navigation-data :branch])
                  (get-in args [:context :branch]))
               (= (get-in state [:navigation-data :query-params :page])
                  (get-in args [:context :query-params :page])))
    state
    (-> state
        (assoc-in [:recent-builds] (:resp args))
        (assoc-in state/project-scopes-path (:scopes args))
        ;; Hack until we have organization scopes
        (assoc-in state/page-scopes-path (or (:scopes args) #{:read-settings})))))


(defmethod api-event [:build :success]
  [target message status args state]
  (mlog "build success: scopes " (:scopes args))
  (let [build (:resp args)
        {:keys [build-num project-name]} (:context args)
        containers (vec (build-model/containers build))]
    (if-not (and (= build-num (:build_num build))
                 (= project-name (vcs-url/project-name (:vcs_url build))))
      state
      (-> state
          (assoc-in state/build-path build)
          (assoc-in state/project-scopes-path (:scopes args))
          (assoc-in state/page-scopes-path (:scopes args))
          (assoc-in (conj (state/project-branch-crumb-path state)
                          :branch)
                    (some-> build :branch utils/encode-branch))
          (assoc-in state/containers-path containers)))))

(defmethod post-api-event! [:build :success]
  [target message status args previous-state current-state]
  (let [{:keys [build-num project-name]} (:context args)]
    ;; This is slightly different than the api-event because we don't want to have to
    ;; convert the build from steps to containers again.
    (when (and (= build-num (get-in args [:resp :build_num]))
               (= project-name (vcs-url/project-name (get-in args [:resp :vcs_url]))))
      (doseq [action (mapcat :actions (get-in current-state state/containers-path))
              :when (and (:has_output action)
                         (action-model/visible? action))]
        (api/get-action-output {:vcs-url (get-in args [:resp :vcs_url])
                                :build-num build-num
                                :step (:step action)
                                :index (:index action)
                                :output-url (:output_url action)}
                               (get-in current-state [:comms :api])))
      (frontend.favicon/set-color! (build-model/favicon-color (get-in current-state state/build-path))))))


(defmethod api-event [:cancel-build :success]
  [target message status args state]
  (let [build-id (get-in args [:context :build-id])]
    (if-not (= (build-model/id (get-in state state/build-path))
               build-id)
      state
      (update-in state state/build-path merge (:resp args)))))


(defmethod api-event [:repos :success]
  [target message status args state]
  ;; prevent delayed api responses if the user has moved on
  (let [login (get-in args [:context :login])
        type (get-in args [:context :type])]
    (assoc-in state (state/repos-path login type) (:resp args))))


(defmethod api-event [:organizations :success]
  [target message status args state]
  (assoc-in state state/user-organizations-path (:resp args)))


(defmethod api-event [:collaborators :success]
  [target message status args state]
  (assoc-in state state/user-collaborators-path (:resp args)))

(defmethod api-event [:tokens :success]
  [target message status args state]
  (print "Tokens received: " args)
  (assoc-in state state/user-tokens-path (:resp args)))

(defmethod api-event [:usage-queue :success]
  [target message status args state]
  (let [usage-queue-builds (:resp args)
        build-id (:context args)]
    (if-not (= build-id (build-model/id (get-in state state/build-path)))
      state
      (assoc-in state state/usage-queue-path usage-queue-builds))))

(defmethod post-api-event! [:usage-queue :success]
  [target message status args previous-state current-state]
  (let [usage-queue-builds (get-in current-state state/usage-queue-path)
        ws-ch (get-in current-state [:comms :ws])]
    (doseq [build usage-queue-builds]
      (put! ws-ch [:subscribe {:channel-name (pusher/build-channel build)
                               :messages [:build/update]}]))))


(defmethod api-event [:build-artifacts :success]
  [target message status args state]
  (let [artifacts (:resp args)
        build-id (:context args)]
    (if-not (= build-id (build-model/id (get-in state state/build-path)))
      state
      (assoc-in state state/artifacts-path artifacts))))


(defmethod api-event [:action-log :success]
  [target message status args state]
  (let [action-log (:resp args)
        {action-index :step container-index :index} (:context args)]
    (-> state
        (assoc-in (state/action-output-path container-index action-index) action-log)
        (update-in (state/action-path container-index action-index) action-model/format-all-output))))


(defmethod api-event [:project-settings :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-name context) (str (get-in state [:navigation-data :org]) "/" (get-in state [:navigation-data :repo])))
    state
    (update-in state state/project-path merge resp)))


(defmethod api-event [:project-plan :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-name context) (str (get-in state [:navigation-data :org]) "/" (get-in state [:navigation-data :repo])))
    state
    (assoc-in state state/project-plan-path resp)))


(defmethod api-event [:project-token :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-name context) (:project-settings-project-name state))
    state
    (assoc-in state state/project-tokens-path resp)))


(defmethod api-event [:project-checkout-key :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-name context) (:project-settings-project-name state))
    state
    (assoc-in state state/project-checkout-keys-path resp)))


(defmethod api-event [:project-envvar :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-name context) (:project-settings-project-name state))
    state
    (assoc-in state state/project-envvars-path resp)))


(defmethod api-event [:update-project-parallelism :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-id context) (project-model/id (get-in state state/project-path)))
    state
    (assoc-in state (conj state/project-data-path :parallelism-edited) true)))


(defmethod api-event [:create-env-var :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-id context) (project-model/id (get-in state state/project-path)))
    state
    (-> state
        (update-in state/project-envvars-path (fnil conj []) resp)
        (assoc-in (conj state/inputs-path :new-env-var-name) "")
        (assoc-in (conj state/inputs-path :new-env-var-value) ""))))


(defmethod api-event [:delete-env-var :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-id context) (project-model/id (get-in state state/project-path)))
    state
    (update-in state state/project-envvars-path (fn [vars]
                                                  (remove #(= (:env-var-name context) (:name %))
                                                          vars)))))


(defmethod api-event [:save-ssh-key :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-id context) (project-model/id (get-in state state/project-path)))
    state
    (assoc-in state (conj state/project-data-path :new-ssh-key) {})))

(defmethod post-api-event! [:save-ssh-key :success]
  [target message status {:keys [context resp]} previous-state current-state]
  (when (= (:project-id context) (project-model/id (get-in current-state state/project-path)))
    (let [project-name (vcs-url/project-name (:project-id context))
          api-ch (get-in current-state [:comms :api])]
      (ajax/ajax :get
                 (gstring/format "/api/v1/project/%s/settings" project-name)
                 :project-settings
                 api-ch
                 :context {:project-name project-name}))))


(defmethod api-event [:delete-ssh-key :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-id context) (project-model/id (get-in state state/project-path)))
    state
    (update-in state (conj state/project-path :ssh_keys)
               (fn [keys]
                 (remove #(= (:fingerprint context) (:fingerprint %))
                         keys)))))


(defmethod api-event [:save-project-api-token :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-id context) (project-model/id (get-in state state/project-path)))
    state
    (-> state
        (assoc-in (conj state/project-data-path :new-api-token) {})
        (update-in state/project-tokens-path (fnil conj []) resp))))


(defmethod api-event [:delete-project-api-token :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-id context) (project-model/id (get-in state state/project-path)))
    state
    (update-in state state/project-tokens-path
               (fn [tokens]
                 (remove #(= (:token %) (:token context))
                         tokens)))))


(defmethod api-event [:set-heroku-deploy-user :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-id context) (project-model/id (get-in state state/project-path)))
    state
    (assoc-in state (conj state/project-path :heroku_deploy_user) (:login context))))


(defmethod api-event [:remove-heroku-deploy-user :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-id context) (project-model/id (get-in state state/project-path)))
    state
    (assoc-in state (conj state/project-path :heroku_deploy_user) nil)))


(defmethod api-event [:first-green-build-github-users :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-name context) (vcs-url/project-name (:vcs_url (get-in state state/build-path))))
    state
    (-> state
        (assoc-in state/build-github-users-path (vec (map-indexed (fn [i u] (assoc u :index i)) resp))))))

(defmethod post-api-event! [:first-green-build-github-users :success]
  [target message status {:keys [resp context]} previous-state current-state]
  ;; This is not ideal, but don't see a better place to put this
  (when (first (remove :following resp))))


(defmethod api-event [:invite-github-users :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-name context) (vcs-url/project-name (:vcs_url (get-in state state/build-path))))
    state
    (assoc-in state state/dismiss-invite-form-path true)))


(defmethod api-event [:enable-project :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-id context) (project-model/id (get-in state state/project-path)))
    state
    (update-in state state/project-path merge (select-keys resp [:has_usable_key]))))


(defmethod api-event [:follow-project :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-id context) (project-model/id (get-in state state/project-path)))
    state
    (assoc-in state (conj state/project-path :followed) true)))


(defmethod api-event [:unfollow-project :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:project-id context) (project-model/id (get-in state state/project-path)))
    state
    (assoc-in state (conj state/project-path :followed) false)))


(defmethod api-event [:org-plan :success]
  [target message status {:keys [resp context]} state]
  (let [org-name (:org-name context)]
    (if-not (= org-name (:org-settings-org-name state))
      state
      (assoc-in state state/org-plan-path resp))))


(defmethod api-event [:org-settings :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:org-name context) (:org-settings-org-name state))
    state
    (-> state
        (update-in state/org-data-path merge resp)
        (assoc-in state/org-loaded-path true)
        (assoc-in state/org-authorized?-path true))))


(defmethod api-event [:org-settings :failed]
  [target message status {:keys [resp context]} state]
  (if-not (= (:org-name context) (:org-settings-org-name state))
    state
    (-> state
        (assoc-in state/org-loaded-path true)
        (assoc-in state/org-authorized?-path false))))

(defmethod api-event [:follow-repo :success]
  [target message status {:keys [resp context]} state]
  (let [{:keys [login type]} context] ; don't pull out :name, to avoid overshadowing
    (if-let [repo-index (state-utils/find-repo-index state login type (:name context))]
      (assoc-in state (conj (state/repo-path login type repo-index) :following) true)
      state)))

(defmethod post-api-event! [:follow-repo :success]
  [target message status args previous-state current-state]
  (api/get-projects (get-in current-state [:comms :api]))
  (if-let [first-build (get-in args [:resp :first_build])]
    (let [nav-ch (get-in current-state [:comms :nav])
          build-path (-> first-build
                         :build_url
                         (goog.Uri.)
                         (.getPath)
                         (subs 1))]
      (put! nav-ch [:navigate! {:path build-path}]))
    (when (repo-model/should-do-first-follower-build? (:context args))
      (ajax/ajax :post
                 (gstring/format "/api/v1/project/%s" (vcs-url/project-name (:vcs_url (:context args))))
                 :start-build
                 (get-in current-state [:comms :api])))))


(defmethod api-event [:unfollow-repo :success]
  [target message status {:keys [resp context]} state]
  (let [{:keys [login type]} context] ; don't pull out :name, to avoid overshadowing
    (if-let [repo-index (state-utils/find-repo-index state login type (:name context))]
      (assoc-in state (conj (state/repo-path login type repo-index) :following) false)
      state)))


(defmethod post-api-event! [:unfollow-repo :success]
  [target message status args previous-state current-state]
  (api/get-projects (get-in current-state [:comms :api])))


(defmethod post-api-event! [:start-build :success]
  [target message status args previous-state current-state]
  (let [nav-ch (get-in current-state [:comms :nav])
        build-url (-> args :resp :build_url (goog.Uri.) (.getPath) (subs 1))]
    (put! nav-ch [:navigate! {:path build-url}])))


(defmethod post-api-event! [:retry-build :success]
  [target message status args previous-state current-state]
  (let [nav-ch (get-in current-state [:comms :nav])
        build-url (-> args :resp :build_url (goog.Uri.) (.getPath) (subs 1))]
    (put! nav-ch [:navigate! {:path build-url}])))


(defmethod post-api-event! [:save-dependencies-commands :success]
  [target message status {:keys [context resp]} previous-state current-state]
  (when (and (= (project-model/id (get-in current-state state/project-path))
                (:project-id context))
             (= :setup (:project-settings-subpage current-state)))
    (let [nav-ch (get-in current-state [:comms :nav])
          org (vcs-url/org-name (:project-id context))
          repo (vcs-url/repo-name (:project-id context))]
      (put! nav-ch [:navigate! {:path (routes/v1-project-settings-subpage {:org org
                                                                           :repo repo
                                                                           :subpage "tests"})}]))))


(defmethod post-api-event! [:save-test-commands-and-build :success]
  [target message status {:keys [context resp]} previous-state current-state]
  (let [controls-ch (get-in current-state [:comms :controls])]
    (put! controls-ch [:started-edit-settings-build context])))


(defmethod api-event [:plan-card :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:org-name context) (:org-settings-org-name state))
    state
    (let [card (or resp {})] ; special case in case card gets deleted
      (assoc-in state state/stripe-card-path card))))


(defmethod api-event [:create-plan :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:org-name context) (:org-settings-org-name state))
    state
    (assoc-in state state/org-plan-path resp)))


(defmethod post-api-event! [:create-plan :success]
  [target message status {:keys [resp context]} previous-state current-state]
  (when (= (:org-name context) (:org-settings-org-name current-state))
    (let [nav-ch (get-in current-state [:comms :nav])]
      (put! nav-ch [:navigate! {:path (routes/v1-org-settings-subpage {:org (:org-name context)
                                                                       :subpage "containers"})
                                :replace-token? true}]))))

(defmethod api-event [:update-plan :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (:org-name context) (:org-settings-org-name state))
    state
    (update-in state state/org-plan-path merge resp)))

(defmethod api-event [:update-heroku-key :success]
  [target message status {:keys [resp context]} state]
  (assoc-in state (conj state/user-path :heroku-api-key-input) ""))

(defmethod api-event [:create-api-token :success]
  [target message status {:keys [resp context]} state]
  (-> state
      (assoc-in state/new-user-token-path "")
      (update-in state/user-tokens-path conj resp)))

(defmethod api-event [:delete-api-token :success]
  [target message status {:keys [resp context]} state]
  (let [deleted-token (:token context)]
    (update-in state state/user-tokens-path (fn [tokens]
                                              (vec (remove #(= (:token %) (:token deleted-token)) tokens))))))
(defmethod api-event [:plan-invoices :success]
  [target message status {:keys [resp context]} state]
  (utils/mlog ":plan-invoices API event: " resp)
  (if-not (= (:org-name context) (:org-settings-org-name state))
    state
    (assoc-in state state/org-invoices-path resp)))

(defmethod api-event [:changelog :success]
  [target message status {:keys [resp context]} state]
  (assoc-in state state/changelog-path {:entries resp :show-id (:show-id context)}))

(defmethod api-event [:build-state :success]
  [target message status {:keys [resp]} state]
  (assoc-in state state/build-state-path resp))

(defmethod api-event [:docs-articles :success]
  [target message status {:keys [resp context]} state]
  (if-not (= (get-in state state/docs-search-path) (:query resp))
    state
    (-> state
        (assoc-in state/docs-articles-results-path (:results resp))
        (assoc-in state/docs-articles-results-query-path (:query resp)))))

(defmethod api-event [:doc-markdown :success]
  [target message status {:keys [resp context] :as data} state]
  (assoc-in state (concat state/docs-data-path [(:subpage context) :markdown]) resp))
