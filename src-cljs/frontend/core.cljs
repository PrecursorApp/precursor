(ns frontend.core
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [frontend.async :refer [put!]]
            [cljs.reader :as reader]
            [clojure.string :as string]
            [datascript :as d]
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
            [frontend.routes :as routes]
            [frontend.controllers.api :as api-con]
            [frontend.controllers.errors :as errors-con]
            [frontend.env :as env]
            [frontend.instrumentation :refer [wrap-api-instrumentation]]
            [frontend.localstorage :as localstorage]
            [frontend.sente :as sente]
            [frontend.state :as state]
            [goog.events]
            [goog.events.EventType]
            [om.core :as om :include-macros true]
            [frontend.history :as history]
            [frontend.browser-settings :as browser-settings]
            [frontend.utils :as utils :refer [mlog merror third] :include-macros true]
            [frontend.utils.ajax :as ajax]
            [frontend.datetime :as datetime]
            [goog.labs.dom.PageVisibilityMonitor]
            [secretary.core :as sec])
  (:require-macros [cljs.core.async.macros :as am :refer [go go-loop alt!]])
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
    (when-not (contains? #{"input" "textarea"} (string/lower-case (.. event -target -tagName)))
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
        document-id (utils/inspect (or (aget js/window "Precursor" "initial-document-id")
                                       ;; TODO: remove after be is fully deployed
                                       (long (last (re-find #"document/(.+)$" (.getPath utils/parsed-uri))))))
        cust (some-> (aget js/window "Precursor" "cust")
               (reader/read-string))
        initial-entities (some-> (aget js/window "Precursor" "initial-entities")
                           (reader/read-string))
        tab-id (utils/uuid)
        sente-id (aget js/window "Precursor" "sente-id")]
    (atom (-> (assoc initial-state
                     ;; id for the browser, used to filter transactions
                     :tab-id tab-id
                     :sente-id sente-id
                     :client-id (str sente-id "-" tab-id)
                     :db (db/make-initial-db initial-entities)
                     :document/id document-id
                     ;; Communicate to nav channel that we shouldn't reset db
                     :initial-state true
                     :cust cust
                     :show-landing? (:show-landing? utils/initial-query-map)
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
            (browser-settings/restore-browser-settings cust)))))

(defn controls-handler
  [value state browser-state]
  (when-not (keyword-identical? :mouse-moved (first value))
    (mlog "Controls Verbose: " value))
  (utils/swallow-errors
   (binding [frontend.async/*uuid* (:uuid (meta value))]
     (let [previous-state @state
           ;; TODO: control-event probably shouldn't get browser-state
           current-state (swap! state (partial controls-con/control-event browser-state (first value) (second value)))]
       (controls-con/post-control-event! browser-state (first value) (second value) previous-state current-state)
       ;; TODO: enable a way to set the event separate from the control event
       (analytics/track-control (first value) current-state)))))

(defn nav-handler
  [value state history]
  (mlog "Navigation Verbose: " value)
  (utils/swallow-errors
   (binding [frontend.async/*uuid* (:uuid (meta value))]
     (let [previous-state @state]
       (swap! state (partial nav-con/navigated-to history (first value) (second value)))
       (nav-con/post-navigated-to! history (first value) (second value) previous-state @state)))))

(defn api-handler
  [value state container]
  (mlog "API Verbose: " (first value) (second value) (utils/third value))
  (utils/swallow-errors
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
  (mlog "Errors Verbose: " value)
  (utils/swallow-errors
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

(defn find-app-container []
  (goog.dom/getElement "om-app"))

(def om-setup-debug (constantly false))

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
        suppressed-key-combos    #{"meta+A" "meta+D" "meta+Z" "shift+meta+Z" "backspace"
                                   "shift+meta+D" "up" "down" "left" "right" "meta+G"}
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
                                                                           :handle-key-up      handle-key-up})]

    ;; allow figwheel in dev-cljs access to this function
    (def om-setup-debug om-setup)

    (swap! state assoc :undo-state undo-state)

    (js/document.addEventListener "keydown" handle-key-down false)
    (js/document.addEventListener "keyup" handle-key-up false)
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
            (async/timeout 10000) (do #_(print "TODO: print out history: ")))))))

(defn fetch-entity-ids [api-ch eid-count]
  (ajax/ajax :post "/api/entity-ids" :entity-ids api-ch :params {:count eid-count}))

(defn notify-error [state]
  (d/transact! (:db @state) [{:layer/type :layer.type/text
                              :layer/name "Error"
                              :layer/text "There was an error connecting to the server."
                              :layer/start-x 200
                              :layer/start-y 200
                              :db/id -1
                              :layer/end-x 600
                              :layer/end-y 175}
                             {:layer/type :layer.type/text
                              :layer/name "Error"
                              :layer/text "Please refresh to try again."
                              :layer/start-x 200
                              :layer/start-y 225
                              :db/id -2
                              :layer/end-x 400
                              :layer/end-y 200}]))

(defn setup-entity-id-fetcher [state]
  (let [api-ch (-> state deref :comms :api)]
    (go (let [resp (<! (ajax/managed-ajax :post "/api/entity-ids" :params {:count 40}))]
          (if (= :success (:status resp))
            (do
              (put! api-ch [:entity-ids :success {:resp resp :status :success}])
              (add-watch state :entity-id-fetcher (fn [key ref old new]
                                                    (when (> 35 (-> new :entity-ids count))
                                                      (utils/mlog "fetching more entity ids")
                                                      (fetch-entity-ids api-ch (- 40
                                                                                  (-> new :entity-ids count)))))))
            (do (notify-error state)
                (js/Rollbar.error "entity ids request failed :(")))))))

(defn ^:export setup! []
  (when-not (utils/logging-enabled?)
    (println "To enable logging, set Precursor['logging-enabled'] = true"))
  (js/React.initializeTouchEvents true)
  (let [state (app-state)
        history-imp (history/new-history-imp)]
    ;; globally define the state so that we can get to it for debugging
    (def debug-state state)
    (sente/init state)
    (browser-settings/setup! state)
    (main state history-imp)
    (when (:cust @state)
      (analytics/init-user (:cust @state)))
    (setup-entity-id-fetcher state)
    (sec/dispatch! (str "/" (.getToken history-imp)))))

(defn ^:export inspect-state []
  (clj->js @debug-state))

(defn ^:export test-rollbar []
  (utils/swallow-errors (throw (js/Error. "This is an exception"))))

(defonce startup (setup!))
