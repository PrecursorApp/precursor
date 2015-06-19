(ns frontend.components.forms
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [frontend.async :refer [put!]]
            [frontend.components.common :as common]
            [frontend.utils :as utils :include-macros true]
            [om.core :as om :include-macros true])
  (:require-macros [cljs.core.async.macros :as am :refer [go go-loop alt!]]
                   [sablono.core :refer (html)]))


;; Version 2 of the stateful button follows. New code should prefer this version, managed-button, over the old version, stateful-button.
;; Example usage:

;; component html:
;; [:form (forms/managed-button [:input {:type "submit" :on-click #(put! controls-ch [:my-control])}])]

;; controls handler:
;; (defmethod post-control-event! :my-control
;;   [target message args previous-state current-state]
;;   (let [status (do-something)
;;         uuid frontend.async/*uuid*]
;;     (forms/release-button! uuid status)))

(def registered-channels (atom {}))

(defn register-channel! [owner]
  (let [channel (chan)
        uuid (utils/squuid)]
    (swap! registered-channels assoc uuid channel)
    (om/update-state! owner [:registered-channel-uuids] #(conj % uuid))
    {:channel channel :uuid uuid}))

(defn deregister-channel! [owner uuid]
  (om/update-state! owner [:registered-channel-uuids] #(disj % uuid))
  (when-let [channel (get @registered-channels uuid)]
    (swap! registered-channels dissoc uuid)
    (close! channel)))

(defn release-button!
  "Used by the controls controller to set the button state. status should be a valid button state,
  :success, :failed, or :idle"
  [uuid status]
  (when-let [channel (get @registered-channels uuid)]
    (put! channel status)))

(defn append-cycle
  "Adds the button-state to the end of the lifecycle"
  [owner button-state]
  (om/update-state! owner [:lifecycle] #(conj % button-state)))

(defn wrap-managed-button-handler
  "Wraps the on-click handler with a uuid binding and registers a global channel
   so that the controls handler can communicate that it is finished with the button."
  [handler owner]
  (fn [& args]
    (append-cycle owner :loading)
    (let [{:keys [uuid channel]} (register-channel! owner)]
      (binding [frontend.async/*uuid* uuid]
        (go (append-cycle owner (<! channel))
            (deregister-channel! owner uuid))
        (apply handler args)))))

(defn schedule-idle
  "Transistions the state from success/failed to idle."
  [owner lifecycle]
  ;; Clear timer, just in case. No harm in clearing nil or finished timers
  (js/clearTimeout (om/get-state owner [:idle-timer]))
  (let [cycle-count (count lifecycle)
        t (js/setTimeout
           ;; Careful not to transition to idle if the spinner somehow got
           ;; back to a loading state. This shouldn't happen, but we'll be
           ;; extra careful.
           #(om/update-state! owner [:lifecycle]
                              (fn [cycles]
                                (if (= (count cycles) cycle-count)
                                  (conj cycles :idle)
                                  cycles)))
           1000)]
    (om/set-state! owner [:idle-timer] t)))

(defn managed-button*
  "Takes an ordinary input or button hiccup form.
   Automatically disables the button until the controls handler calls release-button!"
  [hiccup-form owner]
  (reify
    om/IDisplayName (display-name [_] "Managed button")
    om/IInitState
    (init-state [_]
      {:lifecycle [:idle]
       :registered-channel-uuids #{}
       :idle-timer nil})

    om/IWillUnmount
    (will-unmount [_]
      (js/clearTimeout (om/get-state owner [:idle-timer]))
      (doseq [uuid (om/get-state owner [:registered-channel-uuids])]
        (deregister-channel! owner uuid)))

    om/IWillUpdate
    (will-update [_ _ {:keys [lifecycle]}]
      (when (#{:success :failed} (last lifecycle))
        (schedule-idle owner lifecycle)))

    om/IRenderState
    (render-state [_ {:keys [lifecycle]}]
      (let [button-state (last lifecycle)
            [tag attrs & rest] hiccup-form
            data-field (keyword (str "data-" (name button-state) "-text"))
            new-value (-> (merge {:data-loading-text "..."
                                  :data-success-text "Saved"
                                  :data-failed-text "Failed"}
                                 attrs)
                          (get data-field (:value attrs)))
            new-body (cond (= :idle button-state) rest
                           (:data-spinner attrs) common/spinner
                           :else new-value)
            new-attrs (-> attrs
                          ;; Disable the button when it's not idle
                          ;; We're changing the value of the button, so its safer not to let
                          ;; people click on it.
                          (assoc :disabled (not= :idle button-state))
                          (update-in [:class] (fn [c] (cond (= :idle button-state) c
                                                            (string? c) (str c  " disabled")
                                                            (coll? c) (conj c "disabled")
                                                            :else "disabled")))
                          (update-in [:on-click] wrap-managed-button-handler owner)
                          (update-in [:value] (fn [v]
                                                (or new-value v))))]
        (html
         (vec (concat [tag new-attrs]
                      [new-body])))))))

(defn managed-button
  "Takes an ordinary input or button hiccup form.
   Disables the button while the controls handler waits for any API responses to come back.
   When the button is clicked, it replaces the button value with data-loading-text,
   when the response comes back, and the control handler calls release-button! it replaces the
   button with the data-:status-text for a second."
  [hiccup-form]
  (om/build managed-button* hiccup-form))


;; Version 1 of the stateful button

(defn tap-api
  "Sets up a tap of the api channel and watches for the API request associated with
  the form submission to complete.
  Runs success-fn if the API call was succesful and failure-fn if it failed.
  Will "
  [api-mult api-tap uuid {:keys [success-fn failure-fn api-count]
                          :or {api-count 1}}]
  (async/tap api-mult api-tap)
  (go-loop [api-calls 0 ; keep track of how many api-calls we handled
            results #{}]
           (let [v (<! api-tap)]
             (let [message-uuid (:uuid (meta v))
                   message (first v)
                   status (second v)]
               (cond
                (and (= uuid message-uuid)
                     (#{:success :failed} status))
                (if (and (= status :success) (< (inc api-calls) api-count))
                  (do (utils/mlog "completed" (inc api-calls) "of" api-count "api calls")
                      (recur (inc api-calls) (conj results status)))
                  (do (if (= #{:success} (conj results status))
                        (success-fn)
                        (failure-fn))
                      ;; There's a chance of a race if the button gets clicked twice.
                      ;; No good ideas on how to fix it, and it shouldn't happen,
                      ;; so punting for now
                      (async/untap api-mult api-tap)))

                (nil? v) nil ;; don't recur on closed channel

                :else (recur api-calls results))))))

(defn cleanup
  "Cleans up api-tap channel and stops the idle timer from firing"
  [owner]
  (close! (om/get-state owner [:api-tap]))
  (js/clearTimeout (om/get-state owner [:idle-timer])))

(defn wrap-handler
  "Wraps the on-click handler with a uuid binding to trace the channel passing, and taps
  the api channel so that we can wait for the api call to finish successfully."
  [handler owner api-count]
  (let [api-tap (om/get-state owner [:api-tap])
        api-mult (om/get-shared owner [:comms :api-mult])]
    (fn [& args]
      (append-cycle owner :loading)
      (let [uuid (utils/squuid)]
        (binding [frontend.async/*uuid* uuid]
          (tap-api api-mult api-tap uuid
                   {:api-count api-count
                    :success-fn
                    #(append-cycle owner :success)
                    :failure-fn
                    #(append-cycle owner :failed)})
          (apply handler args))))))

(defn stateful-button*
  "Takes an ordinary input or button hiccup form.
  Disables the button while it waits for the API response to come back.
  When the button is clicked, it replaces the button value with data-loading-text,
  when the response comes back, it replaces the button with the data-:status-text for a second."
  [hiccup-form owner]
  (reify
    om/IDisplayName (display-name [_] "Stateful button")
    om/IInitState
    (init-state [_]
      {:lifecycle [:idle]
       ;; use a sliding-buffer so that we don't block
       :api-tap (chan (sliding-buffer 10))
       :idle-timer nil})

    om/IWillUnmount (will-unmount [_] (cleanup owner))

    om/IWillUpdate
    (will-update [_ _ {:keys [lifecycle]}]
      (when (#{:success :failed} (last lifecycle))
        (schedule-idle owner lifecycle)))

    om/IRenderState
    (render-state [_ {:keys [lifecycle]}]
      (let [button-state (last lifecycle)
            [tag attrs & rest] hiccup-form
            data-field (keyword (str "data-" (name button-state) "-text"))
            new-value (-> (merge {:data-loading-text "..."
                                  :data-success-text "Saved"
                                  :data-failed-text "Failed"}
                                 attrs)
                          (get data-field (:value attrs)))
            new-body (cond (= :idle button-state) rest
                           (:data-spinner attrs) common/spinner
                           :else new-value)
            api-count (get attrs :data-api-count 1) ; number of api calls to wait for
            new-attrs (-> attrs
                          ;; Disable the button when it's not idle
                          ;; We're changing the value of the button, so its safer not to let
                          ;; people click on it.
                          (assoc :disabled (not= :idle button-state))
                          (update-in [:class] (fn [c] (cond (= :idle button-state) c
                                                            (string? c) (str c  " disabled")
                                                            (coll? c) (conj c "disabled")
                                                            :else "disabled")))
                          ;; Update the on-click handler to watch the api channel for success
                          (update-in [:on-click] wrap-handler owner api-count)
                          (update-in [:value] (fn [v]
                                                (or new-value v))))]
        (html
         (vec (concat [tag new-attrs]
                      [new-body])))))))

(defn stateful-button
  "Takes an ordinary input or button hiccup form.
   Disables the button while it waits for the API response to come back.
   When the button is clicked, it replaces the button value with data-loading-text,
   when the response comes back, it replaces the button with the data-:status-text for a second.
   If the button needs to wait for multiple api calls to complete, add data-api-count"
  [hiccup-form]
  (om/build stateful-button* hiccup-form))
