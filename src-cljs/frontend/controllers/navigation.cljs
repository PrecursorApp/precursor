(ns frontend.controllers.navigation
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.string :as str]
            [frontend.async :refer [put!]]
            [frontend.api :as api]
            [frontend.changelog :as changelog]
            [frontend.favicon]
            [frontend.pusher :as pusher]
            [frontend.state :as state]
            [frontend.stefon :as stefon]
            [frontend.utils.ajax :as ajax]
            [frontend.utils.state :as state-utils]
            [frontend.utils.vcs-url :as vcs-url]
            [frontend.utils :as utils :refer [mlog merror]]
            [goog.dom]
            [goog.string :as gstring]
            [goog.style])
  (:require-macros [frontend.utils :refer [inspect]]
                   [dommy.macros :refer [sel sel1]]
                   [cljs.core.async.macros :as am :refer [go go-loop alt!]]))

;; TODO we could really use some middleware here, so that we don't forget to
;;      assoc things in state on every handler
;;      We could also use a declarative way to specify each page.

;; --- Helper Methods ---

(defn set-page-title! [& [title]]
  (set! (.-title js/document) (utils/strip-html
                               (if title
                                 (str title  " - CircleCI")
                                 "CircleCI"))))

(defn scroll-to-fragment!
  "Scrolls to the element with id of fragment, if one exists"
  [fragment]
  (when-let [node (goog.dom.getElement fragment)]
    (let [body (sel1 "body")
          node-top (goog.style/getPageOffsetTop node)
          body-top (goog.style/getPageOffsetTop body)]
      (set! (.-scrollTop body) (- node-top body-top)))))

(defn scroll!
  "Scrolls to fragment if the url had one, or scrolls to the top of the page"
  [args]
  (if (:_fragment args)
    ;; give the page time to render
    (utils/rAF #(scroll-to-fragment! (:_fragment args)))
    (utils/rAF #(set! (.-scrollTop (sel1 "body")) 0))))

;; --- Navigation Multimethod Declarations ---

(defmulti navigated-to
  (fn [history-imp navigation-point args state] navigation-point))

(defmulti post-navigated-to!
  (fn [history-imp navigation-point args previous-state current-state]
    (frontend.favicon/reset!)
    (put! (get-in current-state [:comms :ws]) [:unsubscribe-stale-channels])
    navigation-point))

;; --- Navigation Multimethod Implementations ---

(defn navigated-default [navigation-point args state]
  (-> state
      state-utils/clear-page-state
      (assoc :navigation-point navigation-point
             :navigation-data args)))

(defmethod navigated-to :default
  [history-imp navigation-point args state]
  (navigated-default navigation-point args state))

(defn post-default [navigation-point args]
  (set-page-title! (or (:_title args)
                       (str/capitalize (name navigation-point))))
  (scroll! args))

(defmethod post-navigated-to! :default
  [history-imp navigation-point args previous-state current-state]
  (post-default navigation-point args))

(defmethod navigated-to :navigate!
  [history-imp navigation-point args state]
  state)

(defmethod post-navigated-to! :navigate!
  [history-imp navigation-point {:keys [path replace-token?]} previous-state current-state]
  (let [path (if (= \/ (first path))
               (subs path 1)
               path)]
    (if replace-token? ;; Don't break the back button if we want to redirect someone
      (.replaceToken history-imp path)
      (.setToken history-imp path))))


(defmethod navigated-to :dashboard
  [history-imp navigation-point args state]
  (-> state
      state-utils/clear-page-state
      (assoc :navigation-point navigation-point
             :navigation-data args
             :navigation-settings {:show-settings-link false}
             :recent-builds nil)
      (state-utils/set-dashboard-crumbs args)
      state-utils/reset-current-build
      state-utils/reset-current-project))

(defmethod post-navigated-to! :dashboard
  [history-imp navigation-point args previous-state current-state]
  (let [api-ch (get-in current-state [:comms :api])
        projects-loaded? (seq (get-in current-state state/projects-path))
        current-user (get-in current-state state/user-path)]
    (mlog (str "post-navigated-to! :dashboard with current-user? " (not (empty? current-user))
               " projects-loaded? " (not (empty? projects-loaded?))))
    (when (and (not projects-loaded?)
               (not (empty? current-user)))
      (api/get-projects api-ch))
    (go (let [builds-url (api/dashboard-builds-url (assoc (:navigation-data current-state)
                                                     :builds-per-page (:builds-per-page current-state)))
              api-resp (<! (ajax/managed-ajax :get builds-url))
              scopes (:scopes api-resp)
              _ (mlog (str "post-navigated-to! :dashboard, " builds-url " scopes " scopes))
              comms (get-in current-state [:comms])]
          (condp = (:status api-resp)
            :success (put! (:api comms) [:recent-builds :success (assoc api-resp :context args)])
            :failed (put! (:nav comms) [:error {:status (:status-code api-resp) :inner? false}])
            (put! (:errors comms) [:api-error api-resp]))
          (when (and (:repo args) (:read-settings scopes))
            (ajax/ajax :get
                       (gstring/format "/api/v1/project/%s/%s/settings" (:org args) (:repo args))
                       :project-settings
                       api-ch
                       :context {:project-name (str (:org args) "/" (:repo args))})
            (ajax/ajax :get
                       (gstring/format "/api/v1/project/%s/%s/plan" (:org args) (:repo args))
                       :project-plan
                       api-ch
                       :context {:project-name (str (:org args) "/" (:repo args))})))))
  (set-page-title!))

(defmethod post-navigated-to! :build-state
  [history-imp navigation-point args previous-state current-state]
  (let [api-ch (get-in current-state [:comms :api])]
    (when-not (seq (get-in current-state state/projects-path))
      (api/get-projects api-ch))
    (ajax/ajax :get "/api/v1/admin/build-state" :build-state api-ch))
  (set-page-title! "Build State"))

(defmethod navigated-to :build
  [history-imp navigation-point {:keys [project-name build-num org repo] :as args} state]
  (mlog "navigated-to :build with args " args)
  (-> state
      state-utils/clear-page-state
      (assoc :navigation-point navigation-point
             :navigation-data args
             :navigation-settings {:show-settings-link false}
                                        ; start out false, api-events
                                        ; will set true with
                                        ; appropriate scopes
             :project-settings-project-name project-name)
      (assoc-in state/crumbs-path [{:type :org :username org}
                                   {:type :project :username org :project repo}
                                   {:type :project-branch :username org :project repo}
                                   {:type :build :username org :project repo
                                    :build-num build-num}])
      state-utils/reset-current-build
      (#(if (state-utils/stale-current-project? % project-name)
          (state-utils/reset-current-project %)
          %))))

(defmethod post-navigated-to! :build
  [history-imp navigation-point {:keys [project-name build-num] :as args} previous-state current-state]
  (let [api-ch (get-in current-state [:comms :api])
        ws-ch (get-in current-state [:comms :ws])
        nav-ch (get-in current-state [:comms :nav])
        err-ch (get-in current-state [:comms :errors])
        projects-loaded? (seq (get-in current-state state/projects-path))
        current-user (get-in current-state state/user-path)]
    (mlog (str "post-navigated-to! :build current-user? " (not (empty? current-user))
               " projects-loaded? " (not (empty? projects-loaded?))))
    (when (and (not projects-loaded?)
               (not (empty? current-user)))
      (api/get-projects api-ch))
    (go (let [build-url (gstring/format "/api/v1/project/%s/%s" project-name build-num)
              api-result (<! (ajax/managed-ajax :get build-url))
              scopes (:scopes api-result)]
          (mlog (str "post-navigated-to! :build, " build-url " scopes " scopes))
          ;; Start 404'ing on non-existent builds, as well as when you
          ;; try to go to a build page of a project which doesn't
          ;; exist. This is different than current behaviour, where
          ;; you see the "regular" inner page, with an error message
          ;; where the build info would be. Thoughts?
          (condp = (:status api-result)
            :success (put! api-ch [:build (:status api-result) (assoc api-result :context {:project-name project-name :build-num build-num})])
            :failed (put! nav-ch [:error {:status (:status-code api-result) :inner? false}])
            (put! err-ch [:api-error api-result]))
          (when (= :success (:status api-result)))
          (when (and (not (get-in current-state state/project-path))
                     (:repo args) (:read-settings scopes))
            (ajax/ajax :get
                       (gstring/format "/api/v1/project/%s/settings" project-name)
                       :project-settings
                       api-ch
                       :context {:project-name project-name}))
          (when (and (not (get-in current-state state/project-plan-path))
                     (:repo args) (:read-settings scopes))
            (ajax/ajax :get
                       (gstring/format "/api/v1/project/%s/plan" project-name)
                       :project-plan
                       api-ch
                       :context {:project-name project-name}))))
    (put! ws-ch [:subscribe {:channel-name (pusher/build-channel-from-parts {:project-name project-name
                                                                             :build-num build-num})
                             :messages pusher/build-messages}]))
  (set-page-title! (str project-name " #" build-num)))


(defmethod navigated-to :add-projects
  [history-imp navigation-point args state]
  (-> state
      state-utils/clear-page-state
      (assoc :navigation-point navigation-point :navigation-data args :navigation-settings {})))

(defmethod post-navigated-to! :add-projects
  [history-imp navigation-point _ previous-state current-state]
  (let [api-ch (get-in current-state [:comms :api])]
    (when-not (seq (get-in current-state state/projects-path))
      (api/get-projects api-ch))
    (go (let [api-result (<! (ajax/managed-ajax :get "/api/v1/user/organizations"))]
          (put! api-ch [:organizations (:status api-result) api-result])
          (when-let [first-org (first (:resp api-result))]
            (put! (get-in current-state [:comms :controls]) [:selected-add-projects-org {:login (:login first-org) :type :org}]))))
    (ajax/ajax :get "/api/v1/user/collaborator-accounts" :collaborators api-ch))
  (set-page-title! "Add projects"))


(defmethod navigated-to :project-settings
  [history-imp navigation-point {:keys [project-name subpage org repo] :as args} state]
  (-> state
      state-utils/clear-page-state
      (assoc :navigation-point navigation-point
             :navigation-data args
             :navigation-settings {}
             ;; TODO can we get rid of project-settings-subpage in favor of navigation-data?
             :project-settings-subpage subpage
             :project-settings-project-name project-name)
      (assoc-in state/crumbs-path [{:type :org
                                    :username org}
                                   {:type :project
                                    :username org
                                    :project repo}
                                   {:type :project-settings
                                    :username org
                                    :project repo}])
      (#(if (state-utils/stale-current-project? % project-name)
          (state-utils/reset-current-project %)
          %))))

(defmethod post-navigated-to! :landing
  [history-imp navigation-point _ previous-state current-state]
  (set-page-title! "Continuous Integration and Deployment"))

(defmethod post-navigated-to! :project-settings
  [history-imp navigation-point {:keys [project-name subpage]} previous-state current-state]
  (let [api-ch (get-in current-state [:comms :api])]
    (when-not (seq (get-in current-state state/projects-path))
      (api/get-projects api-ch))
    (if (get-in current-state state/project-path)
      (mlog "project settings already loaded for" project-name)
      (ajax/ajax :get
                 (gstring/format "/api/v1/project/%s/settings" project-name)
                 :project-settings
                 api-ch
                 :context {:project-name project-name}))

    (cond (and (= subpage :parallel-builds)
               (not (get-in current-state state/project-plan-path)))
          (ajax/ajax :get
                     (gstring/format "/api/v1/project/%s/plan" project-name)
                     :project-plan
                     api-ch
                     :context {:project-name project-name})

          (and (= subpage :checkout)
               (not (get-in current-state state/project-checkout-keys-path)))
          (ajax/ajax :get
                     (gstring/format "/api/v1/project/%s/checkout-key" project-name)
                     :project-checkout-key
                     api-ch
                     :context {:project-name project-name})

          (and (#{:api :badges} subpage)
               (not (get-in current-state state/project-tokens-path)))
          (ajax/ajax :get
                     (gstring/format "/api/v1/project/%s/token" project-name)
                     :project-token
                     api-ch
                     :context {:project-name project-name})

          (and (= subpage :env-vars)
               (not (get-in current-state state/project-envvars-path)))
          (ajax/ajax :get
                     (gstring/format "/api/v1/project/%s/envvar" project-name)
                     :project-envvar
                     api-ch
                     :context {:project-name project-name})
          :else nil))

  (set-page-title! (str "Project settings - " project-name)))


(defmethod navigated-to :org-settings
  [history-imp navigation-point {:keys [subpage org] :as args} state]
  (mlog "Navigated to subpage:" subpage)
  (-> state
      state-utils/clear-page-state
      (assoc :navigation-point navigation-point)
      (assoc :navigation-data args)
      (assoc :org-settings-subpage subpage)
      (assoc :org-settings-org-name org)
      (#(if (state-utils/stale-current-org? % org)
          (state-utils/reset-current-org %)
          %))))

(defmethod post-navigated-to! :org-settings
  [history-imp navigation-point {:keys [org subpage]} previous-state current-state]
  (let [api-ch (get-in current-state [:comms :api])]
    (when-not (seq (get-in current-state state/projects-path))
      (api/get-projects api-ch))
    (if (get-in current-state state/org-plan-path)
      (mlog "plan details already loaded for" org)
      (ajax/ajax :get
                 (gstring/format "/api/v1/organization/%s/plan" org)
                 :org-plan
                 api-ch
                 :context {:org-name org}))
    (if (= org (get-in current-state state/org-name-path))
      (mlog "organization details already loaded for" org)
      (ajax/ajax :get
                 (gstring/format "/api/v1/organization/%s/settings" org)
                 :org-settings
                 api-ch
                 :context {:org-name org}))
    (condp = subpage
      :organizations (ajax/ajax :get "/api/v1/user/organizations" :organizations api-ch)
      :billing (do
                 (ajax/ajax :get
                            (gstring/format "/api/v1/organization/%s/card" org)
                            :plan-card
                            api-ch
                            :context {:org-name org})
                 (ajax/ajax :get
                            (gstring/format "/api/v1/organization/%s/invoices" org)
                            :plan-invoices
                            api-ch
                            :context {:org-name org}))
      nil))
  (set-page-title! (str "Org settings - " org)))

(defmethod navigated-to :logout
  [history-imp navigation-point _ state]
  (state-utils/clear-page-state state))

(defmethod post-navigated-to! :logout
  [history-imp navigation-point _ previous-state current-state]
  (go (let [api-result (<! (ajax/managed-ajax :post "/logout"))]
        (set! js/window.location "/"))))


(defmethod navigated-to :error
  [history-imp navigation-point {:keys [status] :as args} state]
  (let [orig-nav-point (get-in state [:navigation-point])]
    (mlog "navigated-to :error with (:navigation-point state) of " orig-nav-point)
    (-> state
        state-utils/clear-page-state
        (assoc :navigation-point navigation-point
               :navigation-data args
               :original-navigation-point orig-nav-point))))

(defmethod post-navigated-to! :error
  [history-imp navigation-point {:keys [status] :as args} previous-state current-state]
  (set-page-title! (condp = status
                     401 "Login required"
                     404 "Page not found"
                     500 "Internal server error"
                     "Something unexpected happened")))

(defmethod post-navigated-to! :changelog
  [history-imp navigation-point args previous-state current-state]
  (scroll! args)
  (go (let [comms (get-in current-state [:comms])
            api-result (<! (ajax/managed-ajax :get "/changelog.rss" :format :xml :response-format :xml))]
        (if (= :success (:status api-result))
          (do (put! (:api comms) [:changelog :success {:resp (changelog/parse-changelog-document (:resp api-result))
                                                       :context {:show-id (:id args)}}])
              ;; might need to scroll to the fragment
              (utils/rAF #(scroll! args)))
          (put! (:errors comms) [:api-error api-result])))))

(defmethod navigated-to :account
  [history-imp navigation-point {:keys [subpage] :as args} state]
  (mlog "Navigated to account subpage:" subpage)
  (-> state
      state-utils/clear-page-state
      (assoc :navigation-point navigation-point)
      (assoc :navigation-data args)
      (assoc :account-settings-subpage subpage)))

(defmethod post-navigated-to! :account
  [history-imp navigation-point {:keys [org-name subpage]} previous-state current-state]
  (let [api-ch (get-in current-state [:comms :api])]
    (when-not (seq (get-in current-state state/projects-path))
      (api/get-projects (get-in current-state [:comms :api])))
    (ajax/ajax :get "/api/v1/user/organizations" :organizations api-ch)
    (ajax/ajax :get "/api/v1/user/token" :tokens api-ch)
    (set-page-title! "Account")))

(defmethod post-navigated-to! :language-landing
  [history-imp navigation-point {:keys [language] :as args} previous-state current-state]
  (post-default navigation-point args))
