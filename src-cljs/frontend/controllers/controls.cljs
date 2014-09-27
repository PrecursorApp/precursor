(ns frontend.controllers.controls
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [cljs.reader :as reader]
            [frontend.analytics :as analytics]
            [frontend.analytics.mixpanel :as mixpanel]
            [frontend.api :as api]
            [frontend.async :refer [put!]]
            [frontend.components.forms :refer [release-button!]]
            [frontend.models.action :as action-model]
            [frontend.models.project :as project-model]
            [frontend.models.build :as build-model]
            [frontend.intercom :as intercom]
            [frontend.routes :as routes]
            [frontend.state :as state]
            [frontend.stripe :as stripe]
            [frontend.utils.ajax :as ajax]
            [frontend.utils.vcs-url :as vcs-url]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.seq :refer [dissoc-in]]
            [frontend.utils.state :as state-utils]
            [goog.string :as gstring]
            [goog.labs.userAgent.engine :as engine]
            goog.style)
  (:require-macros [dommy.macros :refer [sel sel1]]
                   [cljs.core.async.macros :as am :refer [go go-loop alt!]])
  (:import [goog.fx.dom.Scroll]))

;; --- Helper Methods ---

(defn container-id [container]
  (int (last (re-find #"container_(\d+)" (.-id container)))))


(defn extract-from
  "Extract data from a nested map. Returns a new nested map comprising only the
  nested keys from `path`.

  user=> (extract-from nil nil)
  nil
  user=> (extract-from nil [])
  nil
  user=> (extract-from nil [:a])
  nil
  user=> (extract-from {} [:a])
  nil
  user=> (extract-from {:a 1} [:a])
  {:a 1}
  user=> (extract-from {:a {:b {:c 1}}, :d 2} [:a :b])
  {:a {:b {:c 1}}}"
  [m path]
  (when (seq path)
    (let [sentinel (js-obj)
          value (get-in m path sentinel)]
      (when-not (identical? value sentinel)
        (assoc-in {} path value)))))

(defn merge-settings
  "Merge new settings from inputs over a subset of project settings."
  [paths project settings]
  (letfn []
    (if (not (seq paths))
      settings
      (utils/deep-merge (apply merge {} (map (partial extract-from project) paths))
                        settings))))

;; --- Navigation Multimethod Declarations ---

(defmulti control-event
  ;; target is the DOM node at the top level for the app
  ;; message is the dispatch method (1st arg in the channel vector)
  ;; state is current state of the app
  ;; return value is the new state
  (fn [target message args state] message))

(defmulti post-control-event!
  (fn [target message args previous-state current-state] message))

;; --- Navigation Multimethod Implementations ---

(defmethod control-event :default
  [target message args state]
  (utils/mlog "Unknown controls: " message)
  state)

(defmethod post-control-event! :default
  [target message args previous-state current-state]
  (utils/mlog "No post-control for: " message))


(defmethod control-event :user-menu-toggled
  [target message _ state]
  (update-in state [:settings :menus :user :open] not))


(defmethod control-event :show-all-branches-toggled
  [target message value state]
  (assoc-in state state/show-all-branches-path value))

(defmethod control-event :collapse-branches-toggled
  [target message {:keys [project-id project-id-hash]} state]
  ;; Lets us store this in localstorage without leaking info about the user
  (update-in state (state/project-branches-collapsed-path project-id-hash) not))

(defmethod control-event :slim-aside-toggled
  [target message {:keys [project-id]} state]
  (update-in state state/slim-aside-path not))

(defmethod post-control-event! :slim-aside-toggled
  [target message {:keys [project-id]} previous-state current-state]
  (if (get-in current-state state/slim-aside-path)
    (analytics/track-expand-nav)
    (analytics/track-collapse-nav)))

(defmethod control-event :show-admin-panel-toggled
  [target message _ state]
  (update-in state state/show-admin-panel-path not))

(defmethod control-event :instrumentation-line-items-toggled
  [target message _ state]
  (update-in state state/show-instrumentation-line-items-path not))

(defmethod control-event :clear-instrumentation-data-clicked
  [target message _ state]
  (assoc-in state state/instrumentation-path []))

(defmethod control-event :show-inspector-toggled
  [target message _ state]
  (update-in state state/show-inspector-path not))

(defmethod control-event :user-options-toggled
  [target message _ state]
  (update-in state state/user-options-shown-path not))

(defmethod control-event :state-restored
  [target message path state]
  (let [str-data (.getItem js/sessionStorage "circle-state")]
    (if (seq str-data)
      (-> str-data
          reader/read-string
          (assoc :comms (:comms state)))
      state)))


(defmethod control-event :usage-queue-why-toggled
  [target message {:keys [build-id]} state]
  (update-in state state/show-usage-queue-path not))

(defmethod post-control-event! :usage-queue-why-toggled
  [target message {:keys [username reponame
                          build_num build-id]} previous-state current-state]
  (when (get-in current-state state/show-usage-queue-path)
    (let [api-ch (get-in current-state [:comms :api])]
      (api/get-usage-queue (get-in current-state state/build-path) api-ch))))


(defmethod control-event :selected-add-projects-org
  [target message args state]
  (-> state
      (assoc-in [:settings :add-projects :selected-org] args)
      (assoc-in [:settings :add-projects :repo-filter-string] "")))

(defmethod post-control-event! :selected-add-projects-org
  [target message args previous-state current-state]
  (let [login (:login args)
        type (:type args)
        api-ch (get-in current-state [:comms :api])]
    (ajax/ajax :get
               (gstring/format "/api/v1/user/%s/%s/repos" (name type) login)
               :repos
               api-ch
               :context args)))


(defmethod control-event :show-artifacts-toggled
  [target message build-id state]
  (update-in state state/show-artifacts-path not))

(defmethod post-control-event! :show-artifacts-toggled
  [target message _ previous-state current-state]
  (when (get-in current-state state/show-artifacts-path)
    (let [api-ch (get-in current-state [:comms :api])
          build (get-in current-state state/build-path)]
      (ajax/ajax :get
                 (gstring/format "/api/v1/project/%s/%s/artifacts"
                                 (vcs-url/project-name (:vcs_url build))
                                 (:build_num build))
                 :build-artifacts
                 api-ch
                 :context (build-model/id build)))))


(defmethod control-event :show-config-toggled
  [target message build-id state]
  (update-in state state/show-config-path not))


(defmethod control-event :container-selected
  [target message {:keys [container-id]} state]
  (assoc-in state state/current-container-path container-id))

(defmethod post-control-event! :container-selected
  [target message {:keys [container-id animate?] :or {animate? true}} previous-state current-state]
  (when-let [parent (sel1 target "#container_parent")]
    (let [container (sel1 target (str "#container_" container-id))
          body (sel1 target "body")
          current-scroll-top (.-scrollTop parent)
          body-scroll-top (.-scrollTop body)
          current-scroll-left (.-scrollLeft parent)
          new-scroll-left (int (.-x (goog.style.getContainerOffsetToScrollInto container parent)))]
      (let [scroller (or (.-scroll_handler parent)
                         (set! (.-scroll_handler parent)
                               ;; Store this on the parent so that we don't handle parent scroll while
                               ;; the animation is playing
                               (goog.fx.dom.Scroll. parent
                                                    #js [0 0]
                                                    #js [0 0]
                                                    (if animate? 250 0))))
            onEnd (.-onEnd scroller)]
        (set! (.-startPoint scroller) #js [current-scroll-left 0])
        (set! (.-endPoint scroller) #js [new-scroll-left 0])
        ;; Browser find can scroll an absolutely positioned container into view,
        ;; causing the parent to scroll. But then we set it to relative and there
        ;; is no longer any overflow, so we need to scroll app-main instead.
        (set! (.-onEnd scroller) #(do (.call onEnd scroller)
                                      (set! (.-scrollTop body)
                                            (+ body-scroll-top current-scroll-top))))
        (.play scroller))))
  (when (not= (get-in previous-state state/current-container-path)
              container-id)
    (let [container (get-in current-state (state/container-path container-id))
          last-action (-> container :actions last)]
      (when (and (:has_output last-action)
                 (action-model/visible? last-action)
                 (:missing-pusher-output last-action))
        (api/get-action-output {:vcs-url (:vcs_url (get-in current-state state/build-path))
                                :build-num (:build_num (get-in current-state state/build-path))
                                :step (:step last-action)
                                :index (:index last-action)
                                :output-url (:output_url last-action)}
                               (get-in current-state [:comms :api]))))))


(defmethod control-event :action-log-output-toggled
  [target message {:keys [index step value]} state]
  (assoc-in state (state/show-action-output-path index step) value))

(defmethod post-control-event! :action-log-output-toggled
  [target message {:keys [index step] :as args} previous-state current-state]
  (let [action (get-in current-state (state/action-path index step))
        build (get-in current-state state/build-path)]
    (when (and (action-model/visible? action)
               (:has_output action)
               (not (:output action)))
      (api/get-action-output {:vcs-url (:vcs_url build)
                              :build-num (:build_num build)
                              :step step
                              :index index
                              :output-url (:output_url action)}
                             (get-in current-state [:comms :api])))))


(defmethod control-event :selected-project-parallelism
  [target message {:keys [project-id parallelism]} state]
  (assoc-in state (conj state/project-path :parallel) parallelism))

(defmethod post-control-event! :selected-project-parallelism
  [target message {:keys [project-id parallelism]} previous-state current-state]
  (when (not= (get-in previous-state state/project-path)
              (get-in current-state state/project-path))
    (let [project-name (vcs-url/project-name project-id)
          api-ch (get-in current-state [:comms :api])]
      ;; TODO: edit project settings api call should respond with updated project settings
      (ajax/ajax :put
                 (gstring/format "/api/v1/project/%s/settings" project-name)
                 :update-project-parallelism
                 api-ch
                 :params {:parallel parallelism}
                 :context {:project-id project-id}))))


(defmethod control-event :dismiss-invite-form
  [target message _ state]
  (assoc-in state state/dismiss-invite-form-path true))


(defmethod control-event :invite-selected-all
  [target message _ state]
  (update-in state state/build-github-users-path (fn [users]
                                                   (vec (map #(assoc % :checked true) users)))))


(defmethod control-event :invite-selected-none
  [target message _ state]
  (update-in state state/build-github-users-path (fn [users]
                                                   (vec (map #(assoc % :checked false) users)))))


(defmethod control-event :dismiss-config-errors
  [target message _ state]
  (assoc-in state state/dismiss-config-errors-path true))


(defmethod control-event :edited-input
  [target message {:keys [value path]} state]
  (assoc-in state path value))


(defmethod control-event :toggled-input
  [target message {:keys [path]} state]
  (update-in state path not))

(defmethod control-event :clear-inputs
  ;; assumes that paths are relative to inputs, e.g. [:new-env-var], not [:inputs :new-env-var]
  [target message {:keys [paths]} state]
  (reduce (fn [state path]
            (dissoc-in state (concat state/inputs-path path)))
          state paths))


(defmethod post-control-event! :intercom-dialog-raised
  [target message dialog-message previous-state current-state]
  (intercom/raise-dialog (get-in current-state [:comms :errors]) dialog-message))


(defmethod post-control-event! :intercom-user-inspected
  [target message criteria previous-state current-state]
  (if-let [url (intercom/user-link)]
    (js/window.open url)
    (print "No matching url could be found from current window.location.pathname")))


(defmethod post-control-event! :state-persisted
  [target message channel-id previous-state current-state]
  (.setItem js/sessionStorage "circle-state"
            (pr-str (dissoc current-state :comms))))


(defmethod post-control-event! :retry-build-clicked
  [target message {:keys [build-num build-id vcs-url clear-cache?] :as args} previous-state current-state]
  (let [api-ch (-> current-state :comms :api)
        org-name (vcs-url/org-name vcs-url)
        repo-name (vcs-url/repo-name vcs-url)]
    (go
     (let [api-result (<! (ajax/managed-ajax :post (gstring/format "/api/v1/project/%s/%s/%s/retry" org-name repo-name build-num)
                                             :params (when clear-cache? {:no-cache true})))]
       (put! api-ch [:retry-build (:status api-result) api-result])
       (when (= :success (:status api-result))
         (analytics/track-trigger-build (:resp api-result) :clear-cache? clear-cache?))))))


(defmethod post-control-event! :ssh-build-clicked
  [target message {:keys [build-num build-id vcs-url] :as args} previous-state current-state]
  (let [api-ch (-> current-state :comms :api)
        org-name (vcs-url/org-name vcs-url)
        repo-name (vcs-url/repo-name vcs-url)]
    (go
     (let [api-result (<! (ajax/managed-ajax :post (gstring/format "/api/v1/project/%s/%s/%s/ssh" org-name repo-name build-num)))]
       (put! api-ch [:retry-build (:status api-result) api-result])
       (when (= :success (:status api-result))
         (analytics/track-trigger-build (:resp api-result) :ssh? true))))))


(defmethod post-control-event! :followed-repo
  [target message repo previous-state current-state]
  (let [api-ch (get-in current-state [:comms :api])]
    (ajax/ajax :post
               (gstring/format "/api/v1/project/%s/follow" (vcs-url/project-name (:vcs_url repo)))
               :follow-repo
               api-ch
               :context repo))
  (analytics/track-follow-repo))


(defmethod post-control-event! :followed-project
  [target message {:keys [vcs-url project-id]} previous-state current-state]
  (let [api-ch (get-in current-state [:comms :api])]
    (ajax/ajax :post
               (gstring/format "/api/v1/project/%s/follow" (vcs-url/project-name vcs-url))
               :follow-project
               api-ch
               :context {:project-id project-id}))
  (analytics/track-follow-project))


(defmethod post-control-event! :unfollowed-repo
  [target message repo previous-state current-state]
  (let [api-ch (get-in current-state [:comms :api])]
    (ajax/ajax :post
               (gstring/format "/api/v1/project/%s/unfollow" (vcs-url/project-name (:vcs_url repo)))
               :unfollow-repo
               api-ch
               :context repo))
  (analytics/track-unfollow-repo))


(defmethod post-control-event! :unfollowed-project
  [target message {:keys [vcs-url project-id]} previous-state current-state]
  (let [api-ch (get-in current-state [:comms :api])]
    (ajax/ajax :post
               (gstring/format "/api/v1/project/%s/unfollow" (vcs-url/project-name vcs-url))
               :unfollow-project
               api-ch
               :context {:project-id project-id}))
  (analytics/track-unfollow-project))


;; XXX: clean this up
(defmethod post-control-event! :container-parent-scroll
  [target message _ previous-state current-state]
  (let [controls-ch (get-in current-state [:comms :controls])
        current-container-id (get-in current-state state/current-container-path 0)
        parent (sel1 target "#container_parent")
        parent-scroll-left (.-scrollLeft parent)
        current-container (sel1 target (str "#container_" current-container-id))
        current-container-scroll-left (int (.-x (goog.style.getContainerOffsetToScrollInto current-container parent)))
        ;; XXX stop making (count containers) queries on each scroll
        containers (sort-by (fn [c] (Math/abs (- parent-scroll-left (.-x (goog.style.getContainerOffsetToScrollInto c parent)))))
                            (sel parent ".container-view"))
        new-scrolled-container-id (if (= parent-scroll-left current-container-scroll-left)
                                    current-container-id
                                    (if-not (engine/isGecko)
                                      ;; Safari and Chrome scroll the found content to the center of the page
                                      (container-id (first containers))
                                      ;; Firefox scrolls the content just into view
                                      ;; if we're scrolling left, then we want the container whose rightmost portion is showing
                                      ;; if we're scrolling right, then we want the container whose leftmost portion is showing
                                      (if (< parent-scroll-left current-container-scroll-left)
                                        (apply min (map container-id (take 2 containers)))
                                        (apply max (map container-id (take 2 containers))))))]
    ;; This is kind of dangerous, we could end up with an infinite loop. Might want to
    ;; do a swap here (or find a better way to structure this!)
    (when (not= current-container-id new-scrolled-container-id)
      (put! controls-ch [:container-selected {:container-id new-scrolled-container-id
                                              :animate? false}]))))


(defmethod post-control-event! :started-edit-settings-build
  [target message {:keys [project-id]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        uuid frontend.async/*uuid*
        comms (get-in current-state [:comms])
        branch (get-in current-state (conj state/inputs-path :settings-branch) (get-in current-state (conj state/project-path :default_branch)))]
    ;; TODO: edit project settings api call should respond with updated project settings
    (go
     (let [api-result (<! (ajax/managed-ajax :post (gstring/format "/api/v1/project/%s/tree/%s" project-name (gstring/urlEncode branch))))]
       (put! (:api comms) [:start-build (:status api-result) api-result])
       (release-button! uuid (:status api-result))))))

(defmethod post-control-event! :created-env-var
  [target message {:keys [project-id]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (ajax/ajax :post
               (gstring/format "/api/v1/project/%s/envvar" project-name)
               :create-env-var
               api-ch
               :params {:name (get-in current-state (conj state/inputs-path :new-env-var-name))
                        :value (get-in current-state (conj state/inputs-path :new-env-var-value))}
               :context {:project-id project-id})))


(defmethod post-control-event! :deleted-env-var
  [target message {:keys [project-id env-var-name]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (ajax/ajax :delete
               (gstring/format "/api/v1/project/%s/envvar/%s" project-name env-var-name)
               :delete-env-var
               api-ch
               :context {:project-id project-id
                         :env-var-name env-var-name})))


(defmethod post-control-event! :saved-dependencies-commands
  [target message {:keys [project-id]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        settings (state-utils/merge-inputs (get-in current-state state/projects-path)
                                           (get-in current-state state/inputs-path)
                                           [:setup :dependencies :post_dependencies])
        org (vcs-url/org-name project-id)
        repo (vcs-url/repo-name project-id)
        uuid frontend.async/*uuid*
        comms (get-in current-state [:comms])]
    (go
     (let [api-result (<! (ajax/managed-ajax :put (gstring/format "/api/v1/project/%s/settings" project-name) :params settings))]
       (if (= :success (:status api-result))
         (let [settings-api-result (<! (ajax/managed-ajax :get (gstring/format "/api/v1/project/%s/settings" project-name)))]
           (put! (:api comms) [:project-settings (:status settings-api-result) (assoc settings-api-result :context {:project-name project-name})])
           (put! (:controls comms) [:clear-inputs {:paths (map vector (keys settings))}])
           (put! (:nav comms) [:navigate! {:path (routes/v1-project-settings-subpage {:org org :repo repo :subpage "tests"})}]))
         (put! (:errors comms) [:api-error api-result]))
       (release-button! uuid (:status api-result))))))


(defmethod post-control-event! :saved-test-commands
  [target message {:keys [project-id start-build?]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        project (get-in current-state state/project-path)
        inputs (get-in current-state state/inputs-path)
        settings (state-utils/merge-inputs project inputs [:test :extra])
        branch (get inputs :settings-branch (:default_branch project))
        org (vcs-url/org-name project-id)
        repo (vcs-url/repo-name project-id)
        uuid frontend.async/*uuid*
        comms (get-in current-state [:comms])]
    (go
     (let [api-result (<! (ajax/managed-ajax :put (gstring/format "/api/v1/project/%s/settings" project-name) :params settings))]
       (if (= :success (:status api-result))
         (let [settings-api-result (<! (ajax/managed-ajax :get (gstring/format "/api/v1/project/%s/settings" project-name)))]
           (put! (:api comms) [:project-settings (:status settings-api-result) (assoc settings-api-result :context {:project-name project-name})])
           (put! (:controls comms) [:clear-inputs {:paths (map vector (keys settings))}])
           (when start-build?
             (let [build-api-result (<! (ajax/managed-ajax :post (gstring/format "/api/v1/project/%s/tree/%s" project-name (gstring/urlEncode branch))))]
               (put! (:api comms) [:start-build (:status build-api-result) build-api-result]))))
         (put! (:errors comms) [:api-error api-result]))
       (release-button! uuid (:status api-result))))))


(defn save-project-settings
  "Takes the state of project settings inputs and PUTs the new settings to
  /api/v1/project/:project/settings.

  `merge-path` is a path into the nested project data-structure. When
  merge-path is non-nil the part of the project data-structure at that path is
  used as the base values for the settings. The new settings from the inputs
  state are merged on top.

  This allows all the settings on a page to be submitted, even if the user only
  modifies one.

  E.g.
  project is
    {:github-info { ... }
     :aws {:keypair {:access_key_id \"access key\"
                     :secret_access_key \"secret key\"}}}

  The user sets a new access key ID so inputs is
    {:aws {:keypair {:access_key_id \"new key id\"}}}

  :merge-path is [:aws :keypair]

  The settings posted to the settings API will be:
    {:aws {:keypair {:access_key_id \"new key id\"
                     :secret_access_key \"secret key\"}}}"
  [project-id merge-paths current-state]
  (let [project-name (vcs-url/project-name project-id)
        comms (get-in current-state [:comms])
        inputs (get-in current-state state/inputs-path)
        project (get-in current-state state/project-path)
        settings (merge-settings merge-paths project inputs)]
    (go
      (let [api-result (<! (ajax/managed-ajax :put (gstring/format "/api/v1/project/%s/settings" project-name) :params settings))]
        (if (= :success (:status api-result))
          (let [settings-api-result (<! (ajax/managed-ajax :get (gstring/format "/api/v1/project/%s/settings" project-name)))]
            (put! (:api comms) [:project-settings (:status settings-api-result) (assoc settings-api-result :context {:project-name project-name})])
            (put! (:controls comms) [:clear-inputs {:paths (map vector (keys settings))}]))
          (put! (:errors comms) [:api-error api-result]))
        api-result))))

(defmethod post-control-event! :saved-project-settings
  [target message {:keys [project-id merge-paths]} previous-state current-state]
  (let [uuid frontend.async/*uuid*]
    (go
      (let [api-result (<! (save-project-settings project-id merge-paths current-state))]
        (release-button! uuid (:status api-result))))))


(defmethod post-control-event! :saved-ssh-key
  [target message {:keys [project-id ssh-key]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (ajax/ajax :post
               (gstring/format "/api/v1/project/%s/ssh-key" project-name)
               :save-ssh-key
               api-ch
               :params ssh-key
               :context {:project-id project-id})))


(defmethod post-control-event! :deleted-ssh-key
  [target message {:keys [project-id fingerprint]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (ajax/ajax :delete
               (gstring/format "/api/v1/project/%s/ssh-key" project-name)
               :delete-ssh-key
               api-ch
               :params {:fingerprint fingerprint}
               :context {:project-id project-id
                         :fingerprint fingerprint})))


(defmethod post-control-event! :test-hook
  [target message {:keys [project-id merge-paths service]} previous-state current-state]
  (let [uuid frontend.async/*uuid*
        project-name (vcs-url/project-name project-id)
        comms (get-in current-state [:comms])]
    (go
      (let [save-result (<! (save-project-settings project-id merge-paths current-state))
            test-result (if (= (:status save-result) :success)
                          (let [test-result (<! (ajax/managed-ajax :post (gstring/format "/api/v1/project/%s/hooks/%s/test" project-name service)
                                                                   :params {:project-id project-id}))]
                            (when (not= (:status test-result) :success)
                              (put! (:errors comms) [:api-error test-result]))
                            test-result)
                          save-result)]
        (release-button! uuid (:status test-result))))))


(defmethod post-control-event! :saved-project-api-token
  [target message {:keys [project-id api-token]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (ajax/ajax :post
               (gstring/format "/api/v1/project/%s/token" project-name)
               :save-project-api-token
               api-ch
               :params api-token
               :context {:project-id project-id})))


(defmethod post-control-event! :deleted-project-api-token
  [target message {:keys [project-id token]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (ajax/ajax :delete
               (gstring/format "/api/v1/project/%s/token/%s" project-name token)
               :delete-project-api-token
               api-ch
               :context {:project-id project-id
                         :token token})))


(defmethod post-control-event! :set-heroku-deploy-user
  [target message {:keys [project-id login]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (ajax/ajax :post
               (gstring/format "/api/v1/project/%s/heroku-deploy-user" project-name)
               :set-heroku-deploy-user
               api-ch
               :context {:project-id project-id
                         :login login})))


(defmethod post-control-event! :removed-heroku-deploy-user
  [target message {:keys [project-id]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        api-ch (get-in current-state [:comms :api])]
    (ajax/ajax :delete
               (gstring/format "/api/v1/project/%s/heroku-deploy-user" project-name)
               :remove-heroku-deploy-user
               api-ch
               :context {:project-id project-id})))


(defmethod post-control-event! :set-user-session-setting
  [target message {:keys [setting value]} previous-state current-state]
  (set! (.. js/window -location -search) (str "?" (name setting) "=" value)))


(defmethod post-control-event! :load-first-green-build-github-users
  [target message {:keys [project-name]} previous-state current-state]
  (ajax/ajax :get
             (gstring/format "/api/v1/project/%s/users" project-name)
             :first-green-build-github-users
             (get-in current-state [:comms :api])
             :context {:project-name project-name}))


(defmethod post-control-event! :invited-github-users
  [target message {:keys [project-name invitees]} previous-state current-state]
  (ajax/ajax :post
             (gstring/format "/api/v1/project/%s/users/invite" project-name)
             :invite-github-users
             (get-in current-state [:comms :api])
             :context {:project-name project-name}
             :params invitees)
  ;; TODO: move all of the tracking stuff into frontend.analytics and let it
  ;;      keep track of which service to send things to
  (mixpanel/track "Sent invitations" {:first_green_build true
                                      :project project-name
                                      :users (map :login invitees)})
  (doseq [u invitees]
    (mixpanel/track "Sent invitation" {:first_green_build true
                                       :project project-name
                                       :login (:login u)
                                       :id (:id u)
                                       :email (:email u)})))

(defmethod post-control-event! :report-build-clicked
  [target message {:keys [build-url]} previous-state current-state]
  (intercom/raise-dialog (get-in current-state [:comms :errors])
                         (gstring/format "I think I found a bug in Circle at %s" build-url)))

(defmethod post-control-event! :cancel-build-clicked
  [target message {:keys [vcs-url build-num build-id]} previous-state current-state]
  (let [api-ch (-> current-state :comms :api)
        org-name (vcs-url/org-name vcs-url)
        repo-name (vcs-url/repo-name vcs-url)]
    (ajax/ajax :post
               (gstring/format "/api/v1/project/%s/%s/%s/cancel" org-name repo-name build-num)
               :cancel-build
               api-ch
               :context {:build-id build-id})))


(defmethod post-control-event! :enabled-project
  [target message {:keys [project-name project-id]} previous-state current-state]
  (ajax/ajax :post
             (gstring/format "/api/v1/project/%s/enable" project-name)
             :enable-project
             (get-in current-state [:comms :api])
             :context {:project-name project-name
                       :project-id project-id}))

(defmethod post-control-event! :extend-trial-clicked
  [target message {:keys [org-name]} previous-state current-state]
  (let [uuid frontend.async/*uuid*
        api-ch (get-in current-state [:comms :api])]
    (go (let [api-result (<! (ajax/managed-ajax
                              :post
                              (gstring/format "/api/v1/organization/%s/extend-trial" org-name)
                              :params {:org-name org-name}))]
          (put! api-ch [:org-plan (:status api-result) (assoc api-result :context {:org-name org-name})])
          (release-button! uuid (:status api-result))))
    (analytics/track-extend-trial)))


(defmethod post-control-event! :new-plan-clicked
  [target message {:keys [containers price description base-template-id]} previous-state current-state]
  (let [stripe-ch (chan)
        uuid frontend.async/*uuid*
        api-ch (get-in current-state [:comms :api])
        org-name (get-in current-state state/org-name-path)]
    (stripe/open-checkout {:price price :description description} stripe-ch)
    (go (let [[message data] (<! stripe-ch)]
          (condp = message
            :stripe-checkout-closed (release-button! uuid :idle)
            :stripe-checkout-succeeded
            (let [card-info (:card data)]
              (put! api-ch [:plan-card :success {:resp card-info
                                                 :context {:org-name org-name}}])
              (let [api-result (<! (ajax/managed-ajax
                                    :post
                                    (gstring/format "/api/v1/organization/%s/%s" org-name "plan")
                                    :params {:token data
                                             :containers containers
                                             :billing-name org-name
                                             :billing-email (get-in current-state (conj state/user-path :selected_email))
                                             :base-template-id base-template-id}))]
                (put! api-ch [:create-plan (:status api-result) (assoc api-result :context {:org-name org-name})])
                (release-button! uuid (:status api-result))))
            nil)))))

(defmethod post-control-event! :new-checkout-key-clicked
  [target message {:keys [project-id project-name key-type]} previous-state current-state]
  (let [uuid frontend.async/*uuid*
        api-ch (get-in current-state [:comms :api])
        err-ch (get-in current-state [:comms :errors])]
    (go (let [api-resp (<! (ajax/managed-ajax
                             :post
                             (gstring/format "/api/v1/project/%s/checkout-key" project-name)
                             :params {:type key-type}))]
          (if (= :success (:status api-resp))
            (let [api-resp (<! (ajax/managed-ajax :get (gstring/format "/api/v1/project/%s/checkout-key" project-name)))]
              (put! api-ch [:project-checkout-key (:status api-resp) (assoc api-resp :context {:project-name project-name})]))
            (put! err-ch [:api-error api-resp]))
          (release-button! uuid (:status api-resp))))))

(defmethod post-control-event! :delete-checkout-key-clicked
  [target message {:keys [project-id project-name fingerprint]} previous-state current-state]
  (let [uuid frontend.async/*uuid*
        api-ch (get-in current-state [:comms :api])
        err-ch (get-in current-state [:comms :errors])]
    (go (let [api-resp (<! (ajax/managed-ajax
                             :delete
                             (gstring/format "/api/v1/project/%s/checkout-key/%s" project-name fingerprint)))]
          (if (= :success (:status api-resp))
            (let [api-resp (<! (ajax/managed-ajax :get (gstring/format "/api/v1/project/%s/checkout-key" project-name)))]
              (put! api-ch [:project-checkout-key (:status api-resp) (assoc api-resp :context {:project-name project-name})]))
            (put! err-ch [:api-error api-resp]))
          (release-button! uuid (:status api-resp))))))

(defmethod post-control-event! :update-containers-clicked
  [target message {:keys [containers]} previous-state current-state]
  (let [uuid frontend.async/*uuid*
        api-ch (get-in current-state [:comms :api])
        org-name (get-in current-state state/org-name-path)]
    (go
     (let [api-result (<! (ajax/managed-ajax
                           :put
                           (gstring/format "/api/v1/organization/%s/%s" org-name "plan")
                           :params {:containers containers}))]
       (put! api-ch [:update-plan (:status api-result) (assoc api-result :context {:org-name org-name})])
       (release-button! uuid (:status api-result))))
    (analytics/track-save-containers)))

(defmethod post-control-event! :save-piggyback-orgs-clicked
  [target message {:keys [selected-piggyback-orgs org-name]} previous-state current-state]
  (let [uuid frontend.async/*uuid*
        api-ch (get-in current-state [:comms :api])]
    (go
     (let [api-result (<! (ajax/managed-ajax
                           :put
                           (gstring/format "/api/v1/organization/%s/%s" org-name "plan")
                           :params {:piggieback-orgs selected-piggyback-orgs}))]
       (put! api-ch [:update-plan (:status api-result) (assoc api-result :context {:org-name org-name})])
       (release-button! uuid (:status api-result))))
    (analytics/track-save-orgs)))

(defmethod post-control-event! :transfer-plan-clicked
  [target message {:keys [to org-name]} previous-state current-state]
  (let [uuid frontend.async/*uuid*
        api-ch (get-in current-state [:comms :api])
        errors-ch (get-in current-state [:comms :errors])
        nav-ch (get-in current-state [:comms :nav])]
    (go
     (let [api-result (<! (ajax/managed-ajax
                           :put
                           (gstring/format "/api/v1/organization/%s/%s" org-name "transfer-plan")
                           :params {:org-name to}))]
       (if-not (= :success (:status api-result))
         (put! errors-ch [:api-error api-result])
         (let [plan-api-result (<! (ajax/managed-ajax :get (gstring/format "/api/v1/organization/%s/plan" org-name)))]
           (put! api-ch [:org-plan (:status plan-api-result) (assoc plan-api-result :context {:org-name org-name})])
           (put! nav-ch [:navigate! {:path (routes/v1-org-settings-subpage {:org org-name
                                                                            :subpage "plan"})}])))
       (release-button! uuid (:status api-result))))))

(defmethod control-event :preferences-updated
  [target message args state]
  (update-in state state/user-path merge args))

(defmethod post-control-event! :preferences-updated
  [target message args previous-state current-state]
  (ajax/ajax
   :put
   "/api/v1/user/save-preferences"
   :update-preferences
   (get-in current-state [:comms :api])
   :params {:basic_email_prefs (get-in current-state (conj state/user-path :basic_email_prefs))
            :selected_email    (get-in current-state (conj state/user-path :selected_email))}))

(defmethod post-control-event! :heroku-key-add-attempted
  [target message args previous-state current-state]
  (let [uuid frontend.async/*uuid*
        api-ch (get-in current-state [:comms :api])]
    (go
     (let [api-result (<! (ajax/managed-ajax
                           :post
                           "/api/v1/user/heroku-key"
                           :params {:apikey (:heroku_api_key args)}))]
       (if (= :success (:status api-result))
         (let [me-result (<! (ajax/managed-ajax :get "/api/v1/me"))]
           (put! api-ch [:update-heroku-key :success api-result])
           (put! api-ch [:me (:status me-result) (assoc me-result :context {})]))
         (put! (get-in current-state [:comms :errors]) [:api-error api-result]))
       (release-button! uuid (:status api-result))))))

(defmethod post-control-event! :api-token-revocation-attempted
  [target message {:keys [token]} previous-state current-state]
  (let [uuid frontend.async/*uuid*
        api-ch (get-in current-state [:comms :api])]
    (go
     (let [api-result (<! (ajax/managed-ajax
                           :delete
                           (gstring/format "/api/v1/user/token/%s" (:token token))
                           :params {}))]
       (put! api-ch [:delete-api-token (:status api-result) (assoc api-result :context {:token token})])
       (release-button! uuid (:status api-result))))))

(defmethod post-control-event! :api-token-creation-attempted
  [target message {:keys [label]} previous-state current-state]
  (let [uuid frontend.async/*uuid*
        api-ch (get-in current-state [:comms :api])]
    (go
     (let [api-result (<! (ajax/managed-ajax
                           :post
                           "/api/v1/user/token"
                           :params {:label label}))]
       (put! api-ch [:create-api-token (:status api-result) (assoc api-result :context {:label label})])
       (release-button! uuid (:status api-result))))))

(defmethod post-control-event! :update-card-clicked
  [target message {:keys [containers price description base-template-id]} previous-state current-state]
  (let [stripe-ch (chan)
        uuid frontend.async/*uuid*
        api-ch (get-in current-state [:comms :api])
        org-name (get-in current-state state/org-name-path)]
    (stripe/open-checkout {:panelLabel "Update card"} stripe-ch)
    (go (let [[message data] (<! stripe-ch)]
          (condp = message
            :stripe-checkout-closed (release-button! uuid :idle)
            :stripe-checkout-succeeded
            (let [token-id (:id data)]
              (let [api-result (<! (ajax/managed-ajax
                                    :put
                                    (gstring/format "/api/v1/organization/%s/card" org-name)
                                    :params {:token token-id}))]
                (put! api-ch [:plan-card (:status api-result) (assoc api-result :context {:org-name org-name})])
                (release-button! uuid (:status api-result))))
            nil)))))

(defmethod post-control-event! :save-invoice-data-clicked
  [target message _ previous-state current-state]
  (let [uuid frontend.async/*uuid*
        api-ch (get-in current-state [:comms :api])
        org-name (get-in current-state state/org-name-path)
        settings (state-utils/merge-inputs (get-in current-state state/org-plan-path)
                                           (get-in current-state state/inputs-path)
                                           [:billing_email :billing_name :extra_billing_data])]
    (go
      (let [api-result (<! (ajax/managed-ajax
                              :put
                              (gstring/format "/api/v1/organization/%s/plan" org-name)
                              :params {:billing-email (:billing_email settings)
                                       :billing-name (:billing_name settings)
                                       :extra-billing-data (:extra_billing_data settings)}))]
        (when (= :success (:status api-result))
          (put! (get-in current-state [:comms :controls]) [:clear-inputs (map vector (keys settings))]))
        (put! api-ch [:update-plan (:status api-result) (assoc api-result :context {:org-name org-name})])
        (release-button! uuid (:status api-result))))))

(defmethod post-control-event! :resend-invoice-clicked
  [target message {:keys [invoice-id]} previous-state current-state]
  (let [uuid frontend.async/*uuid*
        api-ch (get-in current-state [:comms :api])
        org-name (get-in current-state state/org-name-path)]
    (go
      (let [api-result (<! (ajax/managed-ajax
                              :post
                              (gstring/format "/api/v1/organization/%s/invoice/resend" org-name)
                              :params {:id invoice-id}))]
        ;; TODO Handle this message in the API channel
        (put! api-ch [:resend-invoice (:status api-result) (assoc api-result :context {:org-name org-name})])
        (release-button! uuid (:status api-result))))))

(defmethod post-control-event! :cancel-plan-clicked
  [target message {:keys [org-name cancel-reasons cancel-notes]} previous-state current-state]
  (let [uuid frontend.async/*uuid*
        api-ch (get-in current-state [:comms :api])
        nav-ch (get-in current-state [:comms :nav])
        errors-ch (get-in current-state [:comms :errors])]
    (go
     (let [api-result (<! (ajax/managed-ajax
                           :delete
                           (gstring/format "/api/v1/organization/%s/plan" org-name)
                           :params {:cancel-reasons cancel-reasons :cancel-notes cancel-notes}))]
       (if-not (= :success (:status api-result))
         (put! errors-ch [:api-error api-result])
         (let [plan-api-result (<! (ajax/managed-ajax :get (gstring/format "/api/v1/organization/%s/plan" org-name)))]
           (put! api-ch [:org-plan (:status plan-api-result) (assoc plan-api-result :context {:org-name org-name})])
           (put! nav-ch [:navigate! {:path (routes/v1-org-settings-subpage {:org org-name
                                                                            :subpage "plan"})
                                     :replace-token? true}])))
       (release-button! uuid (:status api-result))))))



(defmethod control-event :home-technology-tab-selected 
  [target message {:keys [tab]} state]
  (assoc-in state state/selected-home-technology-tab-path tab))

(defmethod post-control-event! :home-technology-tab-selected
  [target message {:keys [tab]} previous-state current-state]
  (mixpanel/track "Test Stack" {:tab (name tab)}))

(defmethod post-control-event! :track-external-link-clicked
  [target message {:keys [path event properties]} previous-state current-state]
  (let [redirect #(js/window.location.replace path)]
    (go (alt!
         (mixpanel/managed-track event properties) ([v] (do (utils/mlog "tracked" v "... redirecting")
                                                            (redirect)))
         (async/timeout 1000) (redirect)))))

(defmethod control-event :language-testimonial-tab-selected
  [target message {:keys [index]} state]
  (assoc-in state state/language-testimonial-tab-path index))

(defmethod post-control-event! :enterprise-learn-more-clicked
  [target message {:keys [source]} previous-state current-state]
  (utils/open-modal "#enterpriseModal"))

(defmethod control-event :project-feature-flag-checked
  [target message {:keys [project-id flag value]} state]
  (assoc-in state (conj state/project-path :feature_flags flag) value))

(defmethod post-control-event! :project-feature-flag-checked
  [target message {:keys [project-id flag value]} previous-state current-state]
  (let [project-name (vcs-url/project-name project-id)
        comms (get-in current-state [:comms])]
    (go (let [api-result (<! (ajax/managed-ajax :put (gstring/format "/api/v1/project/%s/settings" project-name)
                                                :params {:feature_flags {flag value}}))]
          (when (not= :success (:status api-result))
            (put! (:errors comms) [:api-error api-result]))
          (api/get-project-settings project-name (:api comms))))))

(defmethod post-control-event! :project-experiments-feedback-clicked
  [target message _ previous-state current-state]
  (intercom/raise-dialog (get-in current-state [:comms :errors])))

(defmethod control-event :refresh-admin-build-state-clicked
  [target message _ state]
  (let [s (assoc-in state state/build-state-path nil)]
    (get-in s state/build-state-path)
    s))

(defmethod post-control-event! :refresh-admin-build-state-clicked
  [target message _ previous-state current-state]
  (ajax/ajax :get "/api/v1/admin/build-state" :build-state (get-in current-state [:comms :api])))

(defmethod control-event :clear-error-message-clicked
  [target message _ state]
  (assoc-in state state/error-message-path nil))

(defmethod control-event :show-all-commits-toggled
  [target message _ state]
  (update-in state (conj state/build-data-path :show-all-commits) not))

(defmethod post-control-event! :doc-search-submitted
  [target message {:keys [query]} previous-state current-state]
  (let [comms (get-in current-state [:comms])]
    (go (let [api-result (<! (ajax/managed-ajax :get "/search-articles" :params {:query query}))]
          (put! (:api comms) [:docs-articles (:status api-result) api-result])
          (when (= (:success (:status api-result)))
            (put! (:nav comms) [:navigate! {:path "/docs"}]))))))
