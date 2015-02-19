(ns frontend.controllers.errors
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.string :as str]
            [datascript :as d]
            [frontend.overlay :as overlay]
            [frontend.camera :as cameras]
            [frontend.state :as state]
            [frontend.utils.ajax :as ajax]
            [frontend.utils.state :as state-utils]
            [frontend.utils.vcs-url :as vcs-url]
            [frontend.utils :as utils :include-macros true]
            [goog.dom]
            [goog.string :as gstring]
            [goog.style])
  (:require-macros [cljs.core.async.macros :as am :refer [go go-loop alt!]]))

;; --- Errors Multimethod Declarations ---

(defmulti error
  (fn [container message args state] message))

(defmulti post-error!
  (fn [container message args previous-state current-state] message))

;; --- Errors Multimethod Implementations ---

(defmethod error :default
  [container message args state]
  (utils/mlog "Unknown error: " message)
  state)

(defmethod post-error! :default
  [container message args previous-state current-state]
  (utils/mlog "No post-error for: " message))

(defn format-unknown-error [{:keys [status-code status-text url] :as args}]
  (gstring/format (str "We got an error "
                       (str "(" (when status-code (str status-code " - ")) status-text ")")
                       (when url (str " while talking to " url))
                       ".")))

(defmethod error :api-error
  [container message {:keys [status-code status-text resp timeout? url] :as args} state]
  (let [message (cond (:message resp) (:message resp)
                      (= status-code 401) :logged-out
                      timeout? (str "A request timed out, talking to " url)
                      :else (format-unknown-error args))]
    (assoc-in state state/error-message-path message)))

(defmethod post-error! :api-error
  [container message args previous-state current-state]
  (when (get-in current-state state/error-message-path)
    (set! (.-scrollTop js/document.body) 0)))

(defmethod error :error-triggered
  [container message error state]
  (assoc-in state state/error-message-path error))

(defmethod post-error! :error-triggered
  [container message args previous-state current-state]
  (when (get-in current-state state/error-message-path)
    (set! (.-scrollTop js/document.body) 0)))

(defmethod error :document-permission-error
  [container message data state]
  ;; When we have more fine-grained permissions, we'll put more info
  ;; into the state
  (-> state
      (overlay/replace-overlay :document-permissions)
      (assoc-in (state/document-access-path (:document/id state)) :none)))

(defn write-error-to-canvas [conn camera error-text]
  (let [[start-x start-y] (cameras/screen->point camera 64 (+ 16 14))]
    (d/transact! conn [{:layer/type :layer.type/text
                        :layer/name "Error"
                        :layer/text error-text
                        :layer/start-x start-x
                        :layer/start-y start-y
                        :db/id 1
                        :layer/end-x 600
                        :layer/end-y 175}]
                 {:bot-layer true})))

(defmethod post-error! :subscribe-to-document-error
  [container message {:keys [document-id]} previous-state current-state]
  (when (:use-frontend-ids current-state)
    (write-error-to-canvas (:db current-state)
                           (:camera current-state)
                           "There was an error connecting to the server.\nPlease refresh to try again."))
  (js/Rollbar.error (str "subscribe to document failed for " document-id)))

(defmethod post-error! :entity-ids-request-failed
  [container message {:keys [document-id]} previous-state current-state]
  (write-error-to-canvas (:db current-state)
                         (:camera current-state)
                         "There was an error connecting to the server.\nPlease refresh to try again.")
  (js/Rollbar.error "entity ids request failed :("))
