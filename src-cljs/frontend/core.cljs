(ns frontend.core
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [frontend.async :refer [put!]]
            [weasel.repl :as ws-repl]
            [clojure.browser.repl :as repl]
            [clojure.string :as string]
            [datascript :as d]
            [dommy.core :as dommy]
            [goog.dom]
            [goog.dom.DomHelper]
            [goog.net.Cookies]
            [frontend.analytics :as analytics]
            [frontend.camera :as camera-helper]
            [frontend.components.app :as app]
            [frontend.controllers.controls :as controls-con]
            [frontend.controllers.navigation :as nav-con]
            [frontend.components.key-queue :as keyq]
            [frontend.datascript :as ds]
            [frontend.routes :as routes]
            [frontend.controllers.api :as api-con]
            [frontend.controllers.errors :as errors-con]
            [frontend.env :as env]
            [frontend.instrumentation :refer [wrap-api-instrumentation]]
            [frontend.sente :as sente]
            [frontend.state :as state]
            [goog.events]
            [goog.events.EventType]
            [om.core :as om :include-macros true]
            [frontend.history :as history]
            [frontend.browser-settings :as browser-settings]
            [frontend.utils :as utils :refer [mlog merror third]]
            [frontend.utils.ajax :as ajax]
            [frontend.datetime :as datetime]
            [goog.labs.dom.PageVisibilityMonitor]
            [secretary.core :as sec])
  (:require-macros [cljs.core.async.macros :as am :refer [go go-loop alt!]]
                   [frontend.utils :refer [inspect timing swallow-errors]])
  (:use-macros [dommy.macros :only [node sel sel1]])
  (:import [cljs.core.UUID]
           [goog.events.EventType]))

(enable-console-print!)

(defn event-props [event]
  {:button  (.-button event)
   :type (.-type event)
   :meta? (.-metaKey event)
   :ctrl? (.-ctrlKey event)})

(defn handle-mouse-move [cast! event]
  (cast! :mouse-moved (conj (camera-helper/screen-event-coords event) (event-props event))
         true))

(defn handle-mouse-down [cast! event]
  (cast! :mouse-depressed (conj (camera-helper/screen-event-coords event) (event-props event)) false))

(defn handle-mouse-up [cast! event]
  (cast! :mouse-released (conj (camera-helper/screen-event-coords event) (event-props event)) false))

(defn disable-mouse-wheel [event]
  (.stopPropagation event))

(defn track-key-state [cast! direction suppressed-key-combos event]
  (let [meta?      (when (.-metaKey event) "meta")
        shift?     (when (.-shiftKey event) "shift")
        ctrl?      (when (.-ctrlKey event) "ctrl")
        alt?       (when (.-altKey event) "alt")
        char       (or (get keyq/code->key (.-which event))
                       (js/String.fromCharCode (.-which event)))
        tokens     [shift? meta? ctrl? alt? char]
        key-string (string/join "+" (filter identity tokens))]
    (when-not (contains? #{"INPUT" "TEXTAREA"} (.. event -target -tagName))
      (when (get suppressed-key-combos key-string)
        (.preventDefault event))
      (when-not (.-repeat event)
        (let [human-name (keyq/event->key event)]
          (let [key-name (keyword (str human-name "?"))]
            (cast! :key-state-changed [{:key-name-kw key-name
                                        :key         human-name
                                        :code        (.-which event)
                                        :depressed?  (= direction :down)}])))))))

;; Overcome some of the browser limitations around DnD
(def mouse-move-ch
  (chan (sliding-buffer 1)))

(def mouse-down-ch
  (chan (sliding-buffer 1)))

(def mouse-up-ch
  (chan (sliding-buffer 1)))

(js/window.addEventListener "mousedown" #(put! mouse-down-ch %))
(js/window.addEventListener "mouseup"   #(put! mouse-up-ch   %))
(js/window.addEventListener "mousemove" #(put! mouse-move-ch %))

(def controls-ch
  (chan))

(def api-ch
  (chan))

(def errors-ch
  (chan))

(def navigation-ch
  (chan))

;; hack to pull document-id out of the url
(def document-id utils/parsed-uri)

(defn app-state []
  (let [initial-state (state/initial-state)
        document-id (long (last (re-find #"document/(.+)$" (.getPath utils/parsed-uri))))
        cust (js->clj (aget js/window "Precursor" "cust") :keywordize-keys true)]
    (atom (assoc initial-state
            :document/id document-id
            ;; id for the browser, used to filter transactions
            ;; TODO: rename client-uuid to something else
            :client-id (UUID. (utils/uuid))
            :db  (ds/make-initial-db document-id)
            :cust cust
            :comms {:controls      controls-ch
                    :api           api-ch
                    :errors        errors-ch
                    :nav           navigation-ch
                    :controls-mult (async/mult controls-ch)
                    :api-mult      (async/mult api-ch)
                    :errors-mult   (async/mult errors-ch)
                    :nav-mult      (async/mult navigation-ch)
                    :mouse-move    {:ch mouse-move-ch
                                    :mult (async/mult mouse-move-ch)}
                    :mouse-down    {:ch mouse-down-ch
                                    :mult (async/mult mouse-down-ch)}
                    :mouse-up      {:ch mouse-up-ch
                                    :mult (async/mult mouse-up-ch)}}))))

(defn log-channels?
  "Log channels in development, can be overridden by the log-channels query param"
  []
  (if (nil? (:log-channels? utils/initial-query-map))
    (env/development?)
    (:log-channels? utils/initial-query-map)))

(defn controls-handler
  [value state browser-state]
  (when true
    (mlog "Controls Verbose: " value))
  (binding [frontend.async/*uuid* (:uuid (meta value))]
    (let [previous-state @state]
      ;; TODO: control-event probably shouldn't get browser-state
      (swap! state (partial controls-con/control-event browser-state (first value) (second value)))
      (controls-con/post-control-event! browser-state (first value) (second value) previous-state @state)
      ;; TODO: enable a way to set the event separate from the control event
      (analytics/track-control (first value)))))

(defn nav-handler
  [value state history]
  (when (log-channels?)
    (mlog "Navigation Verbose: " value))
  (swallow-errors
   (binding [frontend.async/*uuid* (:uuid (meta value))]
     (let [previous-state @state]
       (swap! state (partial nav-con/navigated-to history (first value) (second value)))
       (nav-con/post-navigated-to! history (first value) (second value) previous-state @state)))))

(defn api-handler
  [value state container]
  (when (log-channels?)
    (mlog "API Verbose: " (first value) (second value) (utils/third value)))
  (swallow-errors
   (binding [frontend.async/*uuid* (:uuid (meta value))]
     (let [previous-state @state
           message (first value)
           status (second value)
           api-data (utils/third value)]
       (swap! state (wrap-api-instrumentation (partial api-con/api-event container message status api-data)
                                              api-data))
       (when-let [date-header (get-in api-data [:response-headers "Date"])]
         (datetime/update-server-offset date-header))
       (api-con/post-api-event! container message status api-data previous-state @state)))))

(defn errors-handler
  [value state container]
  (when (log-channels?)
    (mlog "Errors Verbose: " value))
  (swallow-errors
   (binding [frontend.async/*uuid* (:uuid (meta value))]
     (let [previous-state @state]
       (swap! state (partial errors-con/error container (first value) (second value)))
       (errors-con/post-error! container (first value) (second value) previous-state @state)))))

(defn setup-timer-atom
  "Sets up an atom that will keep track of the current time.
   Used from frontend.components.common/updating-duration "
  []
  (let [mya (atom (datetime/server-now))]
    (js/setInterval #(reset! mya (datetime/server-now)) 1000)
    mya))


(defn install-om [state container comms cast! handlers]
  (om/root
     app/app
     state
     {:target container
      :shared {:comms                 comms
               :db                    (:db @state)
               :cast!                 cast!
               :timer-atom            (setup-timer-atom)
               :_app-state-do-not-use state
               :handlers              handlers}}))

(defn find-top-level-node []
  (sel1 :body))

(defn find-app-container [top-level-node]
  (sel1 top-level-node "#om-app"))

(defn main [state top-level-node history-imp]
  (let [comms                    (:comms @state)
        cast!                    (fn [message data & [transient?]]
                                   (put! (:controls comms) [message data transient?]))
        histories                (atom [])
        undo-state               (atom {:transactions []
                                        :last-undo nil})
        container                (find-app-container top-level-node)
        visibility-monitor       (goog.labs.dom.PageVisibilityMonitor.)
        uri-path                 (.getPath utils/parsed-uri)
        history-path             "/"
        controls-tap             (chan)
        nav-tap                  (chan)
        api-tap                  (chan)
        errors-tap               (chan)
        suppressed-key-combos    #{"meta+A" "meta+D" "meta+Z" "shift+meta+Z" "backspace"
                                   "shift+meta+D" "up" "down" "left" "right" "meta+G"}
        handle-key-down          (partial track-key-state cast! :down suppressed-key-combos)
        handle-key-up            (partial track-key-state cast! :up   suppressed-key-combos)
        handle-mouse-move!       #(handle-mouse-move cast! %)
        handle-canvas-mouse-down #(handle-mouse-down cast! %)
        handle-canvas-mouse-up   #(handle-mouse-up   cast! %)
        handle-close!            #(cast! :application-shutdown [@histories])]
    (swap! state assoc :undo-state undo-state)

    (d/listen! (:db @state)
               (fn [tx-report]
                 ;; TODO: figure out why I can send tx-report through controls ch
                 ;; (cast! :db-updated {:tx-report tx-report})
                 (when (first (filter #(= :server/timestamp (:a %)) (:tx-data tx-report)))
                   (cast! :chat-db-updated []))
                 (when (-> tx-report :tx-meta :can-undo?)
                   (swap! undo-state update-in [:transactions] conj tx-report)
                   (when-not (-> tx-report :tx-meta :undo)
                     (swap! undo-state assoc-in [:last-undo] nil)))
                 (when-not (-> tx-report :tx-meta :server-update)
                   (let [datoms (->> tx-report :tx-data (mapv ds/datom-read-api))]
                     (doseq [datom-group (partition-all 500 datoms)]
                       (sente/send-msg (:sente @state) [:frontend/transaction {:datoms datom-group
                                                                               :document/id (:document/id @state)}]))))))

    (js/document.addEventListener "keydown" handle-key-down false)
    (js/document.addEventListener "keyup" handle-key-up false)
    (js/document.addEventListener "mousemove" handle-mouse-move! false)
    (js/window.addEventListener "mouseup"   handle-canvas-mouse-up false)
    (js/window.addEventListener "beforeunload" handle-close!)
    (.addEventListener js/document "mousewheel" disable-mouse-wheel false)
    (.listen visibility-monitor
             goog.events.EventType/VISIBILITYCHANGE
             #(cast! :visibility-changed {:hidden? (.-hidden %)
                                          :visibility-state (.-visibilityState %)})
             false)

    (routes/define-routes! state)
    (install-om state container comms cast! {:handle-mouse-down  handle-canvas-mouse-down
                                             :handle-mouse-up    handle-canvas-mouse-up
                                             :handle-mouse-move! handle-mouse-move!
                                             :handle-key-down    handle-key-down
                                             :handle-key-up      handle-key-up})

    (async/tap (:controls-mult comms) controls-tap)
    (async/tap (:nav-mult comms) nav-tap)
    (async/tap (:api-mult comms) api-tap)
    (async/tap (:errors-mult comms) errors-tap)

    (go (while true
          (alt!
            controls-tap ([v] (controls-handler v state {:container container
                                                         :visibility-monitor visibility-monitor}))
            nav-tap ([v] (nav-handler v state history-imp))
            api-tap ([v] (api-handler v state container))
            errors-tap ([v] (errors-handler v state container))
            ;; Capture the current history for playback in the absence
            ;; of a server to store it
            (async/timeout 10000) (do (print "TODO: print out history: ")))))))

(defn setup-browser-repl [repl-url]
  (when repl-url
    (mlog "setup-browser-repl calling repl/connect with repl-url: " repl-url)
    (repl/connect repl-url))
  ;; this is harmless if it fails
  (ws-repl/connect "ws://localhost:9001" :verbose true)
  ;; the repl tries to take over *out*, workaround for
  ;; https://github.com/cemerick/austin/issues/49
  (js/setInterval #(enable-console-print!) 1000))

(defn apply-app-id-hack
  "Hack to make the top-level id of the app the same as the
   current knockout app. Lets us use the same stylesheet."
  []
  (goog.dom.setProperties (sel1 "#app") #js {:id "om-app"}))

(defn fetch-entity-ids [api-ch eid-count]
  (ajax/ajax :post "/api/entity-ids" :entity-ids api-ch :params {:count eid-count}))

(defn setup-entity-id-fetcher [state]
  (let [api-ch (-> state deref :comms :api)]
    (fetch-entity-ids api-ch 40)
    (add-watch state :entity-id-fetcher (fn [key ref old new]
                                          (when (> 20 (-> new :entity-ids count))
                                            (println "fetching more entity ids")
                                            (fetch-entity-ids api-ch 40))))))

(defn ^:export setup! []
  (apply-app-id-hack)
  (js/React.initializeTouchEvents true)
  (let [state (app-state)
        top-level-node (find-top-level-node)
        history-imp (history/new-history-imp top-level-node)]
    ;; globally define the state so that we can get to it for debugging
    (def debug-state state)
    (browser-settings/setup! state)
    (.set (goog.net.Cookies. js/document) "prcrsr-client-id" (:client-id @state) -1 "/" false)
    ;; TODO: find a better place to put this
    (swap! state (fn [s] (assoc-in s [:camera :offset-x] (if (get-in s state/aside-menu-opened-path)
                                                           (get-in s state/aside-width-path)
                                                           0))))
    (main state top-level-node history-imp)
    (when (:cust @state)
      (analytics/init-user (:cust @state)))
    (sente/init state)
    (setup-entity-id-fetcher state)
    (if-let [error-status (get-in @state [:render-context :status])]
      ;; error codes from the server get passed as :status in the render-context
      (put! (get-in @state [:comms :nav]) [:error {:status error-status}])
      (sec/dispatch! (str "/" (.getToken history-imp))))
    (when (and (env/development?) (= js/window.location.protocol "http:"))
      (try
        (setup-browser-repl (get-in @state [:render-context :browser_connected_repl_url]))
        (catch js/error e
          (merror e))))))

#_(defn reinstall-om! []
  (install-om debug-state (find-app-container (find-top-level-node)) (:comms @debug-state)))

(defn refresh-css! []
  (let [is-app-css? #(re-matches #"/assets/css/app.*?\.css(?:\.less)?" (dommy/attr % :href))
        old-link (->> (sel [:head :link])
                      (filter is-app-css?)
                      first)]
        (dommy/append! (sel1 :head) [:link {:rel "stylesheet" :href "/assets/css/app.css"}])
        (dommy/remove! old-link)))

#_(defn update-ui! []
  (reinstall-om!)
  (refresh-css!))

(setup!)
