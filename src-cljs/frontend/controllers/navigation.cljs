(ns frontend.controllers.navigation
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.string :as str]
            [datascript :as d]
            [frontend.async :refer [put!]]
            [frontend.camera :as cameras]
            [frontend.db :as db]
            [frontend.overlay :as overlay]
            [frontend.replay :as replay]
            [frontend.state :as state]
            [frontend.sente :as sente]
            [frontend.subscribers :as subs]
            [frontend.utils.ajax :as ajax]
            [frontend.utils.state :as state-utils]
            [frontend.utils :as utils :include-macros true]
            [goog.dom]
            [goog.string :as gstring]
            [goog.style])
  (:require-macros [cljs.core.async.macros :as am :refer [go go-loop alt!]]))

;; TODO we could really use some middleware here, so that we don't forget to
;;      assoc things in state on every handler
;;      We could also use a declarative way to specify each page.

;; --- Helper Methods ---

(defn set-page-title! [& [title]]
  (set! (.-title js/document) (utils/strip-html
                               (if title
                                 (str title  " - Precursor")
                                 "Precursor - Simple collaborative prototyping"))))

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
      (update-in [:page-count] inc)))

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

(defn handle-outer [navigation-point args state]
  (-> (navigated-default navigation-point args state)
    (assoc :overlays [])
    (assoc :show-landing? true)))

(defmethod navigated-to :landing
  [history-imp navigation-point args state]
  (handle-outer navigation-point args state))

(defmethod navigated-to :pricing
  [history-imp navigation-point args state]
  (handle-outer navigation-point args state))

(defmethod navigated-to :trial
  [history-imp navigation-point args state]
  (handle-outer navigation-point args state))

(defn handle-camera-params [state {:keys [cx cy x y z] :as query-params}]
  (let [x (when x (js/parseInt x))
        y (when y (js/parseInt y))
        z (or (when z (js/parseFloat z))
              (get-in state [:camera :zf]))
        cx (when cx (js/parseInt cx))
        cy (when cy (js/parseInt cy))
        canvas-size (utils/canvas-size)
        [sx sy] [(/ (:width canvas-size) 2)
                 (/ (:height canvas-size) 2)]]
    (cond-> state
      x (assoc-in [:camera :x] x)
      y (assoc-in [:camera :y] y)
      cx (assoc-in [:camera :x] (- (- cx sx)))
      cy (assoc-in [:camera :y] (- (- cy sy)))
      z (update-in [:camera] cameras/set-zoom [sx sy] (constantly z)))))

(defmethod navigated-to :document
  [history-imp navigation-point args state]
  (let [doc-id (:document/id args)
        initial-entities []]
    (-> (navigated-default navigation-point args state)
        (assoc :document/id doc-id
               :undo-state (atom {:transactions []
                                  :last-undo nil})
               :db-listener-key (utils/uuid)
               :show-landing? false
               :frontend-id-state {})
        (handle-camera-params (:query-params args))
        (subs/add-subscriber-data (:client-id state/subscriber-bot) state/subscriber-bot)
        (#(if-let [overlay (get-in args [:query-params :overlay])]
            (overlay/replace-overlay % (keyword overlay))
            %))
        (assoc :initial-state false)
        ;; TODO: at some point we'll only want to get rid of the layers. Maybe have multiple dbs or
        ;;       find them by doc-id? Will still need a way to clean out old docs.
        (update-in [:db] (fn [db] (if (:initial-state state)
                                    db
                                    (db/reset-db! db initial-entities)))))))

(defmethod post-navigated-to! :document
  [history-imp navigation-point args previous-state current-state]
  (let [sente-state (:sente current-state)
        doc-id (:document/id current-state)]
    (when-let [prev-doc-id (:document/id previous-state)]
      (when (not= prev-doc-id doc-id)
        (sente/send-msg (:sente current-state) [:frontend/unsubscribe {:document-id prev-doc-id}])))
    (if (get-in args [:query-params :replay])
      (replay/replay-and-subscribe current-state :sleep-ms 25)
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
    (sente/update-server-offset sente-state)))

(defmethod navigated-to :new
  [history-imp navigation-point args state]
  (-> (navigated-default navigation-point args state)
    state/reset-state))

(defmethod post-navigated-to! :new
  [history-imp navigation-point _ previous-state current-state]
  (go (let [comms (:comms current-state)
            result (<! (ajax/managed-ajax :post "/api/v1/document/new"))]
        (if (= :success (:status result))
          (put! (:nav comms) [:navigate! {:path (str "/document/" (get-in result [:document :db/id]))}])
          (if (and (= :unauthorized-to-team (get-in result [:response :error]))
                   (get-in result [:response :redirect-url]))
            (set! js/window.location (get-in result [:response :redirect-url]))
            (put! (:errors comms) [:api-error result]))))))
