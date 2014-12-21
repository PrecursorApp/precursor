(ns frontend.core
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [frontend.async :refer [put!]]
            [clojure.string :as string]
            [datascript :as d]
            [dommy.core :as dommy]
            [goog.dom]
            [goog.dom.DomHelper]
            [goog.net.Cookies]
            [frontend.analytics :as analytics]
            [frontend.camera :as camera-helper]
            [frontend.clipboard :as clipboard]
            [frontend.components.app :as app]
            [frontend.controllers.controls :as controls-con]
            [frontend.controllers.navigation :as nav-con]
            [frontend.components.key-queue :as keyq]
            [frontend.datascript :as ds]
            [frontend.db :as db]
            [frontend.dev :as dev]
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
   :ctrl? (.-ctrlKey event)
   :shift? (.-shiftKey event)})

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

(defn app-state []
  (let [initial-state (state/initial-state)
        document-id (long (last (re-find #"document/(.+)$" (.getPath utils/parsed-uri))))
        cust (js->clj (aget js/window "Precursor" "cust") :keywordize-keys true)]
    (atom (-> (assoc initial-state
                ;; id for the browser, used to filter transactions
                ;; TODO: rename client-uuid to something else
                :client-uuid (UUID. (utils/uuid))
                :db  (db/make-initial-db)
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
                                        :mult (async/mult mouse-up-ch)}})
              (browser-settings/restore-browser-settings)))))

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
        handle-close!            #(do (cast! :application-shutdown [@histories])
                                      nil)
        om-setup                 #(install-om state container comms cast! {:handle-mouse-down  handle-canvas-mouse-down
                                                                           :handle-mouse-up    handle-canvas-mouse-up
                                                                           :handle-mouse-move! handle-mouse-move!
                                                                           :handle-key-down    handle-key-down
                                                                           :handle-key-up      handle-key-up})]

    (swap! state assoc :undo-state undo-state)

    (js/document.addEventListener "keydown" handle-key-down false)
    (js/document.addEventListener "keyup" handle-key-up false)
    (js/document.addEventListener "mousemove" handle-mouse-move! false)
    (js/window.addEventListener "mouseup"   handle-canvas-mouse-up false)
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


    (when (and (env/development?) (= js/window.location.protocol "http:"))
      (swallow-errors (dev/setup-figwheel {:js-callback om-setup})))

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
  (js/React.initializeTouchEvents true)
  (let [state (app-state)
        top-level-node (find-top-level-node)
        history-imp (history/new-history-imp top-level-node)]
    ;; globally define the state so that we can get to it for debugging
    (def debug-state state)
    (browser-settings/setup! state)
    (.set (goog.net.Cookies. js/document) "prcrsr-client-id" (:client-uuid @state) -1 "/" false)
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
      (swallow-errors (dev/setup-browser-repl)))))

(defn ^:export inspect-state []
  (clj->js @debug-state))

(defonce startup (setup!))
