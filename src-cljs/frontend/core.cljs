(ns frontend.core
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [cljs.reader :as reader]
            [clojure.string :as string]
            [datascript.core :as d]
            [frontend.ab :as ab]
            [frontend.analytics :as analytics]
            [frontend.async :refer [put!]]
            [frontend.browser-settings :as browser-settings]
            [frontend.camera :as camera-helper]
            [frontend.careful]
            [frontend.clipboard :as clipboard]
            [frontend.components.app :as app]
            [frontend.components.key-queue :as keyq]
            [frontend.config :as config]
            [frontend.controllers.api :as api-con]
            [frontend.controllers.controls :as controls-con]
            [frontend.controllers.errors :as errors-con]
            [frontend.controllers.navigation :as nav-con]
            [frontend.datascript :as ds]
            [frontend.datetime :as datetime]
            [frontend.db :as db]
            [frontend.history :as history]
            [frontend.localstorage :as localstorage]
            [frontend.routes :as routes]
            [frontend.sente :as sente]
            [frontend.state :as state]
            [frontend.talaria :as tal]
            [frontend.team :as team]
            [frontend.utils :as utils :refer [mlog merror third] :include-macros true]
            [frontend.utils.seq :as seq-util]
            [goog.dom]
            [goog.dom.DomHelper]
            [goog.events]
            [goog.events.EventType]
            [goog.labs.dom.PageVisibilityMonitor]
            [goog.net.Cookies]
            [om.core :as om :include-macros true]
            [om-i.core :as omi]
            [secretary.core :as sec])
  (:require-macros [cljs.core.async.macros :as am :refer [go go-loop alt!]])
  (:import [goog.events.EventType]
           [goog.ui IdGenerator]))

(enable-console-print!)

(defn event-props [event]
  {:button  (.-button event)
   :type (.-type event)
   :meta? (.-metaKey event)
   :ctrl? (.-ctrlKey event)
   :shift? (.-shiftKey event)})

(defn handle-mouse-move [cast! event & {:keys [props]}]
  (cast! :mouse-moved (conj (camera-helper/screen-event-coords event) (merge (event-props event) props))
         true))

(defn handle-mouse-down [cast! event & {:keys [props]}]
  (cast! :mouse-depressed (conj (camera-helper/screen-event-coords event) (merge (event-props event) props))
         false))

(defn handle-mouse-up [cast! event & {:keys [props]}]
  (cast! :mouse-released (conj (camera-helper/screen-event-coords event) (merge (event-props event) props))
         false))

(defn disable-mouse-wheel [event]
  (.stopPropagation event))

(defn track-key-state [cast! direction suppressed-key-combos event]
  (let [key-set (keyq/event->key event)]
    (when-not (or (and (contains? #{"input" "textarea"} (string/lower-case (.. event -target -tagName)))
                       ;; dump hack to make copy/paste work in Firefox and Safari (see components.canvas)
                       (not= "_copy-hack" (.. event -target -id)))
                  (= "true" (.. event -target -contentEditable)))
      (when (contains? suppressed-key-combos key-set)
        (.preventDefault event))
      (when-not (and (and (.-repeat event)
                          ;; allow repeat for arrows
                          (not (contains? #{#{"left"}
                                            #{"right"}
                                            #{"up"}
                                            #{"down"}
                                            #{"shift" "left"}
                                            #{"shift" "right"}
                                            #{"shift" "up"}
                                            #{"shift" "down"}}
                                          key-set)))
                     (= "keydown" (.-type event)))
        (cast! :key-state-changed [{:key-set key-set
                                    :code (.-which event)
                                    :depressed? (= direction :down)}])))))

(def controls-ch
  (chan))

(def api-ch
  (chan))

(def errors-ch
  (chan))

(def navigation-ch
  (chan))

(defn app-state []
  (let [initial-state (atom (state/initial-state))
        document-id (or (aget js/window "Precursor" "initial-document-id")
                        ;; TODO: remove after be is fully deployed
                        (when-let [id (last (re-find #"document/(.+)$" (.getPath utils/parsed-uri)))]
                          (long id)))
        cust (some-> (aget js/window "Precursor" "cust")
               (reader/read-string))
        team (some-> (aget js/window "Precursor" "team")
               (reader/read-string))
        admin? (aget js/window "Precursor" "admin?")
        initial-entities (some-> (aget js/window "Precursor" "initial-entities")
                           (reader/read-string))
        initial-issue-entities (some-> (aget js/window "Precursor" "initial-issue-entities")
                                 (reader/read-string))
        tab-id (utils/squuid)
        sente-id (aget js/window "Precursor" "sente-id")
        issue-db (db/make-initial-db initial-issue-entities)
        ab-choices (ab/setup! state/ab-tests)
        use-talaria? (or true
                         (:use-talaria? utils/initial-query-map)
                         (:use-talaria? ab-choices))
        tal (when use-talaria? (tal/init {:secure? (= "https" (.getScheme utils/parsed-uri))
                                          :host (.getDomain utils/parsed-uri)
                                          :port (.getPort utils/parsed-uri)
                                          :params {:tab-id tab-id}
                                          :csrf-token (utils/csrf-token)}
                                         :test-ajax (:use-talaria-ajax? utils/initial-query-map)
                                         :on-reconnect (fn [tal-state]
                                                         (let [s @initial-state]
                                                           (when (:document/id s)
                                                             (sente/subscribe-to-document tal-state
                                                                                          (:comms s)
                                                                                          (:document/id s)
                                                                                          :requested-color (get-in s [:subscribers :info (:client-id s) :color])
                                                                                          :requested-remainder (get-in s [:subscribers :info (:client-id s) :frontend-id-seed :remainder])))))))]
    (utils/inspect (swap! initial-state #(-> (assoc %
                                                    ;; id for the browser, used to filter transactions
                                                    :admin? admin?
                                                    :tab-id tab-id
                                                    :sente-id sente-id
                                                    :client-id (str sente-id "-" tab-id)
                                                    :db (db/make-initial-db initial-entities)
                                                    ;; team entities go into the team namespace, so we need a separate database
                                                    ;; to prevent conflicts
                                                    :team-db (db/make-initial-db nil)
                                                    :issue-db issue-db
                                                    :document/id document-id
                                                    ;; Communicate to nav channel that we shouldn't reset db
                                                    :initial-state true
                                                    :cust cust
                                                    :team team
                                                    :subdomain config/subdomain
                                                    :show-landing? (:show-landing? utils/initial-query-map)
                                                    :ab-choices ab-choices
                                                    :tal tal
                                                    :use-talaria? use-talaria?
                                                    :pessimistic? (utils/inspect (:pessimistic? utils/initial-query-map))
                                                    :comms {:controls      controls-ch
                                                            :api           api-ch
                                                            :errors        errors-ch
                                                            :nav           navigation-ch
                                                            :controls-mult (async/mult controls-ch)
                                                            :api-mult      (async/mult api-ch)
                                                            :errors-mult   (async/mult errors-ch)
                                                            :nav-mult      (async/mult navigation-ch)})
                                             (merge (when use-talaria?
                                                      {:sente tal}))
                                             (browser-settings/restore-browser-settings cust))))
    initial-state))

(defn controls-handler
  [[msg data :as value] state browser-state]
  (when-not (or (keyword-identical? :mouse-moved msg)
                (keyword-identical? :media-stream-volume msg))
    (mlog "Controls Verbose: " value))
  (utils/swallow-errors
   (binding [frontend.async/*uuid* (:uuid (meta value))]
     (let [previous-state @state
           ;; TODO: control-event probably shouldn't get browser-state
           current-state (swap! state (partial controls-con/control-event browser-state msg data))]
       (controls-con/post-control-event! browser-state msg data previous-state current-state)
       ;; TODO: enable a way to set the event separate from the control event
       (analytics/track-control msg data current-state)))))

(defn nav-handler
  [[msg data :as value] state history]
  (mlog "Navigation Verbose: " value)
  (utils/swallow-errors
   (binding [frontend.async/*uuid* (:uuid (meta value))]
     (let [previous-state @state
           current-state (swap! state (partial nav-con/navigated-to history (first value) (second value)))]
       (nav-con/post-navigated-to! history (first value) (second value) previous-state current-state)
       (analytics/track-nav msg data current-state)))))

(defn api-handler
  [value state container]
  (mlog "API Verbose: " (first value) (second value) (utils/third value))
  (utils/swallow-errors
   (binding [frontend.async/*uuid* (:uuid (meta value))]
     (let [previous-state @state
           message (first value)
           status (second value)
           api-data (utils/third value)]
       (swap! state (partial api-con/api-event container message status api-data))
       (api-con/post-api-event! container message status api-data previous-state @state)))))

(defn errors-handler
  [value state container]
  (mlog "Errors Verbose: " value)
  (utils/swallow-errors
   (binding [frontend.async/*uuid* (:uuid (meta value))]
     (let [previous-state @state]
       (swap! state (partial errors-con/error container (first value) (second value)))
       (errors-con/post-error! container (first value) (second value) previous-state @state)))))

(defn install-om [state container comms cast! handlers]
  (omi/setup-component-stats!)
  (om/root
   app/app
   state
   {:target container
    :shared {:comms                 comms
             :db                    (:db @state)
             :team-db               (:team-db @state)
             :issue-db              (:issue-db @state)
             :cast!                 cast!
             :_app-state-do-not-use state
             :handlers              handlers
             ;; Can't log in without a page refresh, have to re-evaluate this if
             ;; that ever changes.
             :cust                  (:cust @state)
             :sente                 (if (:use-talaria? @state)
                                      (:tal @state)
                                      (:sente @state))
             :admin?                (:admin? @state)
             :ab-choices            (:ab-choices @state)
             }
    :instrument (fn [f cursor m]
                  (om/build* f cursor (assoc m :descriptor omi/instrumentation-methods)))}))

(defn find-app-container []
  (goog.dom/getElement "om-app"))

(defn main [state history-imp]
  (let [comms                    (:comms @state)
        cast!                    (fn [message data & [transient?]]
                                   (put! (:controls comms) [message data transient?]))
        histories                (atom [])
        undo-state               (atom {:transactions []
                                        :last-undo nil})
        container                (find-app-container)
        visibility-monitor       (goog.labs.dom.PageVisibilityMonitor.)
        uri-path                 (.getPath utils/parsed-uri)
        history-path             "/"
        controls-tap             (chan)
        nav-tap                  (chan)
        api-tap                  (chan)
        errors-tap               (chan)
        suppressed-key-combos    #{#{"meta" "A"} #{"meta" "D"} #{"meta" "Z"} #{"shift" "meta" "Z"} #{"backspace"}
                                   #{"shift" "meta" "D"} #{"up"} #{"down"} #{"left"} #{"right"} #{"meta" "G"}}
        handle-key-down          (partial track-key-state cast! :down suppressed-key-combos)
        handle-key-up            (partial track-key-state cast! :up   suppressed-key-combos)
        handle-mouse-move        #(handle-mouse-move cast! %)
        handle-canvas-mouse-down #(handle-mouse-down cast! %)
        handle-canvas-mouse-up   #(handle-mouse-up   cast! %)
        handle-close!            #(do (cast! :application-shutdown [@histories])
                                      nil)
        om-setup                 #(install-om state container comms cast! {:handle-mouse-down  handle-canvas-mouse-down
                                                                           :handle-mouse-up    handle-canvas-mouse-up
                                                                           :handle-mouse-move  handle-mouse-move
                                                                           :handle-key-down    handle-key-down
                                                                           :handle-key-up      handle-key-up})
        doc-watcher-id           (.getNextUniqueId (.getInstance IdGenerator))]

    (db/setup-issue-listener! (:issue-db @state) "issue-db" comms (:sente @state))

    ;; This will allow us to update the url and title when the doc name changes
    (db/add-attribute-listener (:db @state) :document/name doc-watcher-id #(cast! :db-document-name-changed {:tx-data (map ds/datom-read-api (:tx-data %))}))

    ;; allow figwheel in dev-cljs access to this function
    (reset! frontend.careful/om-setup-debug om-setup)

    (swap! state assoc :undo-state undo-state)

    (when (get-in @state [:team :team/uuid])
      (team/setup state))

    (js/document.addEventListener "keydown" handle-key-down false)
    (js/document.addEventListener "keyup" handle-key-up false)
    (js/window.addEventListener "mouseup" #(handle-mouse-up   cast! % :props {:outside-canvas? true}) false)
    (js/window.addEventListener "mousedown" #(handle-mouse-down cast! % :props {:outside-canvas? true}) false)
    (js/window.addEventListener "beforeunload" handle-close!)
    (.addEventListener js/document "mousewheel" disable-mouse-wheel false)
    (js/window.addEventListener "copy" #(clipboard/handle-copy! @state %))
    (js/window.addEventListener "paste" #(clipboard/handle-paste! @state %))
    (.listen visibility-monitor
             goog.events.EventType/VISIBILITYCHANGE
             #(cast! :visibility-changed {:hidden? (.-hidden %)
                                          :visibility-state (.-visibilityState %)})
             false)



    (routes/define-routes! state)
    (om-setup)

    (async/tap (:controls-mult comms) controls-tap)
    (async/tap (:nav-mult comms) nav-tap)
    (async/tap (:api-mult comms) api-tap)
    (async/tap (:errors-mult comms) errors-tap)

    (utils/go+
     (while true
       (alt!
         controls-tap ([v] (controls-handler v state {:container container
                                                      :visibility-monitor visibility-monitor
                                                      :history-imp history-imp}))
         nav-tap ([v] (nav-handler v state history-imp))
         api-tap ([v] (api-handler v state container))
         errors-tap ([v] (errors-handler v state container))
         ;; Capture the current history for playback in the absence
         ;; of a server to store it
         (async/timeout 10000) (do #_(print "TODO: print out history: ")))))))

(def tokens-to-ignore ["login" "blog"])

(defn ^:export setup! []
  (when-not (utils/logging-enabled?)
    (println "To enable logging, set Precursor['logging-enabled'] = true"))
  (js/React.initializeTouchEvents true)
  (let [state (app-state)
        history-imp (history/new-history-imp tokens-to-ignore)]
    ;; globally define the state so that we can get to it for debugging
    (def debug-state state)
    (sente/init state)
    (browser-settings/setup! state)
    (main state history-imp)
    (analytics/init @state)
    (sec/dispatch! (str "/" (.getToken history-imp)))))

(defn ^:export inspect-state []
  (clj->js @debug-state))

(defn ^:export test-rollbar []
  (utils/swallow-errors (throw (js/Error. "This is an exception"))))

(defn ^:export print-ab-choices []
  (doseq [[k v] (:ab-choices @frontend.core.debug-state)]
    (println k v)))

(defn ^:export next-choice [test-name]
  (let [test-kw (keyword test-name)
        options (get state/ab-tests test-kw)
        current-choice (get-in @frontend.core.debug-state [:ab-choices test-kw])
        current-index (seq-util/find-index #(= current-choice %)
                                           options)
        next-index (mod (inc current-index) (count options))
        next-choice (nth options next-index)]
    (println "Before:")
    (print-ab-choices)
    (swap! frontend.core.debug-state assoc-in [:ab-choices test-kw] next-choice)
    (@frontend.careful/om-setup-debug)
    (println "After:")
    (print-ab-choices)))

(defonce startup (setup!))
