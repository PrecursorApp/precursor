(ns frontend.controllers.api
  (:require [cljs.core.async :refer [close!]]
            [datascript :as d]
            [frontend.async :refer [put!]]
            [frontend.routes :as routes]
            [frontend.state :as state]
            [frontend.utils.state :as state-utils]
            [frontend.utils :as utils]
            [goog.string :as gstring])
  (:require-macros [frontend.utils :refer [inspect]]))

;; when a button is clicked, the post-controls will make the API call, and the
;; result will be pushed into the api-channel
;; the api controller will do assoc-in
;; the api-post-controller can do any other actions

;; --- API Multimethod Declarations ---

(defmulti api-event
  ;; target is the DOM node at the top level for the app
  ;; message is the dispatch method (1st arg in the channel vector)
  ;; args is the 2nd value in the channel vector)
  ;; state is current state of the app
  ;; return value is the new state
  (fn [target message status args state] [message status]))

(defmulti post-api-event!
  (fn [target message status args previous-state current-state] [message status]))

;; --- API Multimethod Implementations ---

(defmethod api-event :default
  [target message status args state]
  ;; subdispatching for state defaults
  (let [submethod (get-method api-event [:default status])]
    (if submethod
      (submethod target message status args state)
      (do (utils/merror "Unknown api: " message args)
          state))))

(defmethod post-api-event! :default
  [target message status args previous-state current-state]
  ;; subdispatching for state defaults
  (let [submethod (get-method post-api-event! [:default status])]
    (if submethod
      (submethod target message status args previous-state current-state)
      (utils/merror "Unknown api: " message status args))))

(defmethod api-event [:default :success]
  [target message status args state]
  (utils/mlog "No api for" [message status])
  state)

(defmethod post-api-event! [:default :success]
  [target message status args previous-state current-state]
  (utils/mlog "No post-api for: " [message status]))

(defmethod api-event [:default :failed]
  [target message status args state]
  (utils/mlog "No api for" [message status])
  state)

(defmethod post-api-event! [:default :failed]
  [target message status args previous-state current-state]
  (put! (get-in current-state [:comms :errors]) [:api-error args])
  (utils/mlog "No post-api for: " [message status]))

(defmethod api-event [:created-docs :success]
  [target message status {:keys [docs]} state]
  (assoc-in state [:cust :created-docs] docs))

(defmethod api-event [:touched-docs :success]
  [target message status {:keys [docs]} state]
  (assoc-in state [:cust :touched-docs] docs))

(defn clip-compare [clip-1 clip-2]
  (if (:clip/important? clip-1)
    (if (:clip/important? clip-2)
      (compare (d/squuid-time-millis (:clip/uuid clip-2))
               (d/squuid-time-millis (:clip/uuid clip-1)))
      -1)
    (if (:clip/important? clip-2)
      1
      (compare (d/squuid-time-millis (:clip/uuid clip-2))
               (d/squuid-time-millis (:clip/uuid clip-1))))))

(defmethod api-event [:cust-clips :success]
  [target message status {:keys [clips]} state]
  (let [sorted-clips (sort clip-compare clips)]
    (assoc-in state [:cust :cust/clips] sorted-clips)))

(defmethod api-event [:team-docs :success]
  [target message status {:keys [docs]} state]
  (assoc-in state [:team :recent-docs] docs))

(defmethod api-event [:frontend/frontend-id-state :success]
  [target message status {:keys [context frontend-id-state max-document-scope]} state]
  (if (= (:document/id state)
         (:document-id context))
    (assoc state
           :frontend-id-state frontend-id-state
           :max-document-scope max-document-scope)
    (do
      (utils/mlog "document ids don't match")
      state)))

(defmethod api-event [:progress :success]
  [target message status progress-data state]
  (update-in state [:progress] merge progress-data))
