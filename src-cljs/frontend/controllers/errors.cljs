(ns frontend.controllers.errors
  (:require [cljs.core.async :as async :refer [>! <! alts! put! chan sliding-buffer close!]]
            [clojure.string :as str]
            [datascript :as d]
            [frontend.overlay :as overlay]
            [frontend.camera :as cameras]
            [frontend.models.chat :as chat-model]
            [frontend.models.doc :as doc-model]
            [frontend.rtc :as rtc]
            [frontend.rtc.stats :as rtc-stats]
            [frontend.sente :as sente]
            [frontend.state :as state]
            [frontend.urls :as urls]
            [frontend.utils.state :as state-utils]
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
    (set! (.-scrollTop js/document.body) 0))
  (js/Rollbar.error (clj->js args)))

(defmethod error :error-triggered
  [container message error state]
  (assoc-in state state/error-message-path error))

(defmethod post-error! :error-triggered
  [container message args previous-state current-state]
  (when (get-in current-state state/error-message-path)
    (set! (.-scrollTop js/document.body) 0)))

(defmethod error :document/permission-error
  [container message data state]
  ;; When we have more fine-grained permissions, we'll put more info
  ;; into the state
  (-> state
    (assoc-in (state/document-access-path (:document/id state)) :none)
    (overlay/handle-replace-menu :document-permissions)))

(defmethod error :team/permission-error
  [container message data state]
  ;; When we have more fine-grained permissions, we'll put more info
  ;; into the state
  (-> state
    (assoc-in (state/team-access-path (:team/uuid (:team state))) :none)))

(defn write-error-to-canvas [conn camera error-text]
  (let [[start-x start-y] (cameras/screen->point camera 64 (+ 16 14))]
    (d/transact! conn [{:layer/type :layer.type/text
                        :layer/name "Error"
                        :layer/text error-text
                        :layer/start-x start-x
                        :layer/start-y start-y
                        :db/id 1
                        :layer/end-x (+ start-x 300)
                        :layer/end-y (- start-y 23)}]
                 {:bot-layer true})))

(defmethod post-error! :subscribe-to-document-error
  [container message {:keys [document-id]} previous-state current-state]
  (write-error-to-canvas (:db current-state)
                         (:camera current-state)
                         "There was an error connecting to the server.\nPlease refresh to try again.")
  (js/Rollbar.error (str "subscribe to document failed for " document-id)))

(defmethod post-error! :entity-ids-request-failed
  [container message {:keys [document-id]} previous-state current-state]
  (write-error-to-canvas (:db current-state)
                         (:camera current-state)
                         "There was an error connecting to the server.\nPlease refresh to try again.")
  (js/Rollbar.error "entity ids request failed :("))

(defn read-only-rejected? [state rejects datom-group]
  (and (= (count rejects) (count datom-group))
       (= :read (:max-document-scope state))))

(defmethod error :datascript/rejected-datoms
  [container message {:keys [rejects datom-group sente-event]} state]
  (utils/mlog rejects)
  (if (read-only-rejected? state rejects datom-group)
    (-> state
      (assoc-in (state/notified-read-only-path (:document/id state)) true)
      (update-in (state/doc-tx-rejected-count-path (:document/id state)) (fnil inc 0)))
    state))

(defmethod post-error! :datascript/rejected-datoms
  [container message {:keys [rejects datom-group sente-event]} previous-state current-state]
  (when (and (read-only-rejected? previous-state rejects datom-group)
             (nil? (get-in previous-state (state/notified-read-only-path (:document/id previous-state)))))
    (put! (get-in current-state [:comms :nav]) [:navigate! {:path (urls/overlay-path (doc-model/find-by-id @(:db previous-state) (:document/id previous-state))
                                                                                     "sharing")}])))

(defmethod error :datascript/sync-tx-error
  [container message {:keys [reason sente-event datom-group annotations] :as data} state]
  (update-in state [:unsynced-datoms sente-event] (fnil conj []) {:datom-group datom-group
                                                                  :annotations annotations}))

(defmethod post-error! :datascript/sync-tx-error
  [container message {:keys [reason sente-event datom-group] :as data} previous-state current-state]
  (when (= :frontend/transaction sente-event)
    (chat-model/create-bot-chat (:db current-state)
                                current-state
                                [:span "There was an error saving some of your recent changes. Affected shapes are marked with dashed lines. "
                                 [:a {:role "button"
                                      :on-click #(put! (get-in current-state [:comms :controls])
                                                       [:retry-unsynced-datoms {:sente-event :frontend/transaction}])}
                                  "Click here to retry"]
                                 ". You may also want to work on the document in a separate tab, in case there are any persistent problems. "
                                 "Email " [:a {:href "mailto:hi@precursorapp.com?subject=My+changes+aren't+saving"}
                                           "hi@precursorapp.com"]
                                 " for help."]
                                {:error/id :error/sync-tx-error})
    (d/transact! (:db current-state)
                 (mapv (fn [e] [:db/add e :unsaved true])
                       (set (map :e datom-group)))
                 {:bot-layer true}))
  (js/Rollbar.error "sync-tx-error" (clj->js data)))

(defmethod post-error! :rtc-error
  [container message {:keys [type error signal-data]} previous-state current-state]
  (let [{:keys [consumer producer]} signal-data]
    (chat-model/create-bot-chat (:db current-state) current-state
                                (str "There was an error creating the webRTC connection from "
                                     (state-utils/client-id->user current-state consumer)
                                     " to "
                                     (state-utils/client-id->user current-state producer)
                                     ". Please ping @prcrsr in chat if you're having troubles connecting and we'll try to fix it for you.")
                                {:error/id :error/rtc-error})
    (sente/send-msg (:sente current-state) [:rtc/diagnostics (assoc (rtc-stats/gather-stats rtc/conns rtc/stream)
                                                                    :signal-data (select-keys signal-data [:stream-id :consumer :producer])
                                                                    :error-type type)]))
  (js/Rollbar.error (str "rtc error of type " type) error))
