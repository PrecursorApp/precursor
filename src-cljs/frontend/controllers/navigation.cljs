(ns frontend.controllers.navigation
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [cljs-http.client :as http]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [datascript.core :as d]
            [frontend.analytics :as analytics]
            [frontend.async :refer [put!]]
            [frontend.camera :as cameras]
            [frontend.db :as db]
            [frontend.models.doc :as doc-model]
            [frontend.models.issue :as issue-model]
            [frontend.overlay :as overlay]
            [frontend.replay :as replay]
            [frontend.state :as state]
            [frontend.sente :as sente]
            [frontend.subscribers :as subs]
            [frontend.urls :as urls]
            [frontend.utils.state :as state-utils]
            [frontend.utils :as utils :include-macros true]
            [goog.dom]
            [goog.string :as gstring]
            [goog.style]
            [goog.Uri])
  (:require-macros [cljs.core.async.macros :as am :refer [go go-loop alt!]]))

;; TODO we could really use some middleware here, so that we don't forget to
;;      assoc things in state on every handler
;;      We could also use a declarative way to specify each page.

;; --- Navigation Multimethod Declarations ---

(defmulti navigated-to
  (fn [history-imp navigation-point args state] navigation-point))

(defmulti post-navigated-to!
  (fn [history-imp navigation-point args previous-state current-state]
    navigation-point))

;; --- Navigation Multimethod Implementations ---

(defn navigated-default [navigation-point args state]
  (-> state
      (assoc :navigation-point navigation-point
             :navigation-data args)
      (update-in [:page-count] inc)
      (utils/update-when-in [:replay-interrupt-chan] (fn [c]
                                                       (when c
                                                         (put! c :interrupt))
                                                       nil))))

(defmethod navigated-to :default
  [history-imp navigation-point args state]
  (utils/mlog "No navigated-to for" navigation-point)
  (navigated-default navigation-point args state))

(defmethod post-navigated-to! :default
  [history-imp navigation-point args previous-state current-state]
  (utils/mlog "No post-navigated-to! for" navigation-point))

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

(defmethod navigated-to :back!
  [history-imp navigation-point _ state]
  state)

(defmethod post-navigated-to! :back!
  [history-imp navigation-point _ previous-state current-state]
  (.back js/window.history))

(defn handle-outer [navigation-point args state]
  (-> (navigated-default navigation-point args state)
    (assoc :overlays [])
    (assoc :show-landing? true)))

(defmethod navigated-to :landing
  [history-imp navigation-point args state]
  (let [state (handle-outer navigation-point args state)]
    (if (= "designer-news" (:utm-campaign args))
      (assoc-in state state/dn-discount-path true)
      state)))

(defmethod navigated-to :pricing
  [history-imp navigation-point args state]
  (handle-outer navigation-point args state))

(defmethod navigated-to :team-features
  [history-imp navigation-point args state]
  (handle-outer navigation-point args state))

(defmethod navigated-to :trial
  [history-imp navigation-point args state]
  (handle-outer navigation-point args state))

(defn play-replay? [args]
  (and (get-in args [:query-params :replay])
       (if-let [min-width (or (some-> args :query-params :min-width js/parseInt)
                              640)]
         (> (.-width (goog.dom/getViewportSize))
            min-width)
         true)))

(defn handle-doc-navigation [navigation-point args state]
  (let [doc-id (:document/id args)
        initial-entities []
        previous-point (:navigation-point state)
        state (navigated-default navigation-point args state)]
    (if (= doc-id (:loaded-doc state))
      (-> state
        (assoc :show-landing? false
               :outer-to-inner? (:show-landing? state)
               :menu-to-inner? (and (= :overlay previous-point)
                                    (not= :overlay navigation-point))))
      (-> state
        (assoc :document/id doc-id
               :loaded-doc doc-id
               :undo-state (atom {:transactions []
                                  :last-undo nil})
               :db-listener-key (utils/squuid)
               :show-landing? false
               :outer-to-inner? (:show-landing? state)
               :menu-to-inner? (and (= :overlay previous-point)
                                    (not= :overlay navigation-point))
               :frontend-id-state {}
               :replay-interrupt-chan (when (play-replay? args)
                                        (async/chan)))
        (state/reset-camera)
        (state/clear-subscribers)
        (subs/add-subscriber-data (:client-id state/subscriber-bot) state/subscriber-bot)
        (assoc :initial-state false)
        ;; TODO: at some point we'll only want to get rid of the layers. Maybe have multiple dbs or
        ;;       find them by doc-id? Will still need a way to clean out old docs.
        (update-in [:db] (fn [db] (if (:initial-state state)
                                    db
                                    (db/reset-db! db (concat initial-entities
                                                             (map #(utils/update-when-in
                                                                    %
                                                                    [:document/chat-bot]
                                                                    :db/id)
                                                                  (doc-model/all @db)))))))))))

(defn maybe-replace-doc-token [current-state]
  (let [url (goog.Uri. js/window.location)
        path (.getPath url)
        ;; duplicated in controls/replace-token-with-new-name
        current-url-name (second (re-find #"^/document/([A-Za-z0-9_-]*?)-{0,1}\d+(/.*$|$)" path))
        doc (doc-model/find-by-id @(:db current-state)
                                  (:document/id current-state))
        new-url-name (-> doc
                       :document/name
                       urls/urlify-doc-name)]
    (when (seq new-url-name)
      (utils/set-page-title! (:document/name doc)))
    (when (and (seq new-url-name)
               (not= current-url-name new-url-name)
               (zero? (.indexOf path "/document")))
      (let [[_ before-name after-name] (re-find #"^(/document/)[A-Za-z0-9_-]*?-{0,1}(\d+(/.*$|$))" path)
            new-path (str before-name new-url-name "-" after-name)]
        (put! (get-in current-state [:comms :nav]) [:navigate! {:replace-token? true
                                                                :path (str new-path
                                                                           (when (seq (.getQuery url))
                                                                             (str "?" (.getQuery url))))}])))))

(defn handle-post-doc-navigation [navigation-point args previous-state current-state]
  (let [sente-state (:sente current-state)
        doc-id (:document/id current-state)]
    (when-not (= doc-id (:loaded-doc previous-state))
      (when-let [prev-doc-id (:document/id previous-state)]
        (when (not= prev-doc-id doc-id)
          (sente/send-msg (:sente current-state) [:frontend/unsubscribe {:document-id prev-doc-id}])))
      (if (play-replay? args)
        (utils/apply-map replay/replay-and-subscribe
                         current-state
                         (-> {:sleep-ms 25
                              :interrupt-ch (:replay-interrupt-chan current-state)}
                           (merge
                            (select-keys (:query-params args)
                                         [:delay-ms :sleep-ms :tx-count]))
                           (utils/update-when-in [:tx-count] js/parseInt)))
        (sente/subscribe-to-document sente-state (:comms current-state) doc-id))
      ;; TODO: probably only need one listener key here, and can write a fn replace-listener
      (d/unlisten! (:db previous-state) (:db-listener-key previous-state))
      (db/setup-listener! (:db current-state)
                          (:db-listener-key current-state)
                          (:comms current-state)
                          :frontend/transaction
                          {:document/id doc-id}
                          (:undo-state current-state)
                          sente-state)
      (sente/update-server-offset sente-state)
      (put! (get-in current-state [:comms :controls]) [:handle-camera-query-params (select-keys (:query-params args)
                                                                                                [:cx :cy :x :y :z])])
      (put! (get-in current-state [:comms :controls]) [:handle-camera-query-params (select-keys (:query-params args)
                                                                                                [:cx :cy :x :y :z])])
      (maybe-replace-doc-token current-state))))

(defmethod navigated-to :document
  [history-imp navigation-point args state]
  (-> (handle-doc-navigation navigation-point args state)
    overlay/clear-overlays))

(defmethod post-navigated-to! :document
  [history-imp navigation-point args previous-state current-state]
  (handle-post-doc-navigation navigation-point args previous-state current-state))

(defmethod navigated-to :new
  [history-imp navigation-point args state]
  (-> (navigated-default navigation-point args state)
    state/reset-state
    (assoc :page-count 0)))

(defmethod post-navigated-to! :new
  [history-imp navigation-point _ previous-state current-state]
  (go (let [comms (:comms current-state)
            result (<! (http/post "/api/v1/document/new" {:edn-params {}
                                                          :headers {"X-CSRF-Token" (utils/csrf-token)}}))]
        (if (:success result)
          (let [document (-> result :body reader/read-string :document)]
            (d/transact! (:db current-state) [document] {:server-update true})
            (put! (:nav comms) [:navigate! {:path (urls/doc-path document)
                                            :replace-token? true}]))
          (if (and (= :unauthorized-to-team (some-> result :body reader/read-string :error))
                   (some-> result :body reader/read-string :redirect-url))
            (set! js/window.location (some-> result :body reader/read-string :redirect-url))
            (put! (:errors comms) [:api-error result]))))))

(defmulti overlay-extra (fn [state overlay] overlay))
(defmethod overlay-extra :default [state overlay] state)
(defmethod overlay-extra :start [state overlay] (assoc-in state state/main-menu-learned-path true))
(defmethod overlay-extra :info [state overlay] (assoc-in state state/info-button-learned-path true))
(defmethod overlay-extra :doc-viewer [state overlay] (assoc-in state state/your-docs-learned-path true))
(defmethod overlay-extra :sharing [state overlay] (assoc-in state state/sharing-menu-learned-path true))
(defmethod overlay-extra :export [state overlay] (assoc-in state state/export-menu-learned-path true))
(defmethod overlay-extra :shortcuts [state overlay] (assoc-in state state/shortcuts-menu-learned-path true))


(defmulti overlay-extra-post! (fn [previous-state current-state overlay] overlay))
(defmethod overlay-extra-post! :default [previous-state current-state overlay] nil)
(defmethod overlay-extra-post! :info [previous-state current-state overlay]
  (when (and (not (get-in previous-state state/info-button-learned-path))
             (get-in current-state state/info-button-learned-path))
    (analytics/track "What's this learned")))
(defmethod overlay-extra-post! :doc-viewer [previous-state current-state overlay]
  (when (:cust current-state)
    (sente/send-msg
     (:sente current-state)
     [:frontend/fetch-touched]
     10000
     (fn [{:keys [docs]}]
       (when docs
         ;; seems like maybe this should be where I stop. Why are we storing docs in the state?
         ;; Makes it easier to tell which ones are "touched" docs.
         (d/transact! (:db current-state) (map #(dissoc % :last-updated-instant) docs))
         (put! (get-in current-state [:comms :api]) [:touched-docs :success {:docs docs}]))))))

(defmethod overlay-extra-post! :team-doc-viewer [previous-state current-state overlay]
  (when (:cust current-state)
  (sente/send-msg
   (:sente current-state)
   [:team/fetch-touched {:team/uuid (get-in current-state [:team :team/uuid])}]
   10000
   (fn [{:keys [docs]}]
     (when docs
       ;; TODO: if absolute-doc-url ever pulls subdomain out of the db, this could break it
       (d/transact! (:db current-state) (map #(dissoc % :last-updated-instant) docs) {:server-update true})
       (put! (get-in current-state [:comms :api]) [:team-docs :success {:docs docs}]))))))

(defmethod overlay-extra-post! :clips [previous-state current-state overlay]
  (when (:cust current-state)
    (sente/send-msg
     (:sente current-state)
     [:cust/fetch-clips]
     30000
     (fn [{:keys [clips]}]
       (when (seq clips)
         ;; seems like maybe this should be where I stop. Why are we storing docs in the state?
         ;; Makes it easier to tell which ones are "touched" docs.
         (put! (get-in current-state [:comms :api]) [:cust-clips :success {:clips clips}]))))))

(defmethod navigated-to :overlay
  [history-imp navigation-point args state]
  ;; this may be the landing page, in which case we need to load the doc
  (let [overlay (keyword (:overlay args))]
    (-> (handle-doc-navigation navigation-point args state)
      (overlay/handle-add-menu overlay)
      (overlay-extra overlay))))

(defmethod post-navigated-to! :overlay
  [history-imp navigation-point args previous-state current-state]
  (handle-post-doc-navigation navigation-point args previous-state current-state)
  (let [overlay (keyword (:overlay args))]
    (overlay-extra-post! previous-state current-state overlay)))

(defmethod navigated-to :plan-submenu
  [history-imp navigation-point args state]
  ;; this may be the landing page, in which case we need to load the doc
  (-> (handle-doc-navigation navigation-point args state)
    (overlay/handle-add-menu (keyword "plan" (:submenu args)))))

(defmethod navigated-to :issues-list
  [history-imp navigation-point args state]
  (let [issues-list-id (when (= (:navigation-point state)
                                :single-issue)
                         (:issues-list-id state))]
    (-> (navigated-default navigation-point args state)
      (#(if issues-list-id
          (handle-doc-navigation navigation-point (assoc args :document/id issues-list-id) %)
          %))
      (overlay/add-issues-overlay))))

(defmethod post-navigated-to! :issues-list
  [history-imp navigation-point args previous-state current-state]
  (utils/set-page-title! "Feature requests")
  (when-let [issues-list-id (when (= (:navigation-point previous-state)
                                     :single-issue)
                              (:issues-list-id previous-state))]
    (handle-post-doc-navigation navigation-point (assoc args :document/id issues-list-id) previous-state current-state)))

(defmethod navigated-to :issues-completed
  [history-imp navigation-point args state]
  (let [issues-list-id (when (= (:navigation-point state)
                                :single-issue)
                         (:issues-list-id state))]
    (-> (navigated-default navigation-point args state)
      (#(if issues-list-id
          (handle-doc-navigation navigation-point (assoc args :document/id issues-list-id) %)
          %))
      (overlay/handle-add-menu :issues/completed))))

(defmethod post-navigated-to! :issues-completed
  [history-imp navigation-point args previous-state current-state]
  (utils/set-page-title! "Completed feature requests")
  (sente/send-msg (:sente current-state) [:issue/fetch-completed])
  (when-let [issues-list-id (when (= (:navigation-point previous-state)
                                     :single-issue)
                              (:issues-list-id previous-state))]
    (handle-post-doc-navigation navigation-point (assoc args :document/id issues-list-id) previous-state current-state)))

(defmethod navigated-to :issues-search
  [history-imp navigation-point args state]
  (let [issues-list-id (when (= (:navigation-point state)
                                :single-issue)
                         (:issues-list-id state))]
    (-> (navigated-default navigation-point args state)
      (#(if issues-list-id
          (handle-doc-navigation navigation-point (assoc args :document/id issues-list-id) %)
          %))
      (overlay/handle-add-menu :issues/search))))

(defmethod post-navigated-to! :issues-search
  [history-imp navigation-point args previous-state current-state]
  (utils/set-page-title! "Search feature requests")
  (when-let [issues-list-id (when (= (:navigation-point previous-state)
                                     :single-issue)
                              (:issues-list-id previous-state))]
    (handle-post-doc-navigation navigation-point (assoc args :document/id issues-list-id) previous-state current-state)))

(defmethod navigated-to :single-issue
  [history-imp navigation-point args state]
  (let [issue-uuid (uuid (:issue-uuid args))
        issue (issue-model/find-by-frontend-id @(:issue-db state) issue-uuid)
        issues-list-id (when (= (:navigation-point state)
                                :issues-list)
                         (:document/id state))]
    (-> (handle-doc-navigation navigation-point
                               (assoc args :document/id (or (:issue/document issue)
                                                            (:document/id state)))
                               state)
      (assoc :active-issue-uuid issue-uuid)
      (overlay/handle-add-menu :issues/single-issue)
      (cond-> issues-list-id (assoc :issues-list-id issues-list-id)))))

(defmethod post-navigated-to! :single-issue
  [history-imp navigation-point args previous-state current-state]
  (handle-post-doc-navigation navigation-point (assoc args :document/id (:document/id current-state)) previous-state current-state))
