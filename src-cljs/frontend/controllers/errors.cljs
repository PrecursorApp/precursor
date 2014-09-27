(ns frontend.controllers.errors
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.string :as str]
            [frontend.async :refer [put!]]
            [frontend.api :as api]
            [frontend.state :as state]
            [frontend.utils.ajax :as ajax]
            [frontend.utils.state :as state-utils]
            [frontend.utils.vcs-url :as vcs-url]
            [frontend.utils :as utils :include-macros true]
            [goog.dom]
            [goog.string :as gstring]
            [goog.style])
  (:require-macros [dommy.macros :refer [sel sel1]]
                   [cljs.core.async.macros :as am :refer [go go-loop alt!]]))

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
    (set! (.-scrollTop (sel1 "body")) 0)))

(defmethod error :error-triggered
  [container message error state]
  (assoc-in state state/error-message-path error))

(defmethod post-error! :error-triggered
  [container message args previous-state current-state]
  (when (get-in current-state state/error-message-path)
    (set! (.-scrollTop (sel1 "body")) 0)))
