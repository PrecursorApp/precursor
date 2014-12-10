(ns frontend.controllers.navigation
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.string :as str]
            [datascript :as d]
            [frontend.async :refer [put!]]
            [frontend.db :as db]
            [frontend.state :as state]
            [frontend.sente :as sente]
            [frontend.utils.ajax :as ajax]
            [frontend.utils.state :as state-utils]
            [frontend.utils.vcs-url :as vcs-url]
            [frontend.utils :as utils :include-macros true]
            [goog.dom]
            [goog.string :as gstring]
            [goog.style])
  (:require-macros [dommy.macros :refer [sel sel1]]
                   [cljs.core.async.macros :as am :refer [go go-loop alt!]]))

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
             :navigation-data args)))

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

(defmethod navigated-to :document
  [history-imp navigation-point args state]
  (let [doc-id (:document/id args)]
    (-> (navigated-default navigation-point args state)
        (assoc :document/id doc-id
               :undo-state (atom {:transactions []
                                  :last-undo nil})
               :db-listener-key (utils/uuid))
        (update-in (state/doc-chat-bot-path doc-id)
                   #(or % (rand-nth ["daniel" "danny" "prcrsr"])))
        (dissoc :subscribers)
        ;; TODO: at some point we'll only want to get rid of the layers. Maybe have multiple dbs or
        ;;       find them by doc-id? Will still need a way to clean out old docs.
        (update-in [:db] db/reset-db!))))

(defmethod post-navigated-to! :document
  [history-imp navigation-point _ previous-state current-state]
  (let [sente-state (:sente current-state)
        doc-id (:document/id current-state)]
    (when-let [prev-doc-id (:document/id previous-state)]
      (sente/send-msg (:sente current-state) [:frontend/unsubscribe {:document-id prev-doc-id}]))
    (sente/subscribe-to-document sente-state doc-id)
    ;; TODO: probably only need one listener key here, and can write a fn replace-listener
    (d/unlisten! (:db previous-state) (:db-listener-key previous-state))
    (db/setup-listener! (:db current-state)
                        (:db-listener-key current-state)
                        (fn [message data & [transient?]]
                          (put! (get-in current-state [:comms :controls]) [message data transient?]))
                        doc-id
                        (:undo-state current-state)
                        sente-state)))
