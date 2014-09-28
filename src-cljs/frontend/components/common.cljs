(ns frontend.components.common
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [frontend.async :refer [put!]]
            [frontend.datetime :as datetime]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.github :as gh-utils]
            [goog.dom.DomHelper]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]]))

(defn contact-us-inner [controls-ch]
  [:a {:on-click #(put! controls-ch [:intercom-dialog-raised])}
   "contact us"])

(defn flashes
  "Displays common error messages (poorly named since flashes has another use in the app)."
  [error-message owner]
  (reify
    om/IRender
    (render [_]
      (let [controls-ch (om/get-shared owner [:comms :controls])
            ;; use error messages that have html without passing html around
            display-message (condp = error-message
                              :logged-out [:span "You've been logged out, " [:a {:href (gh-utils/auth-url)} "log back in"] " to continue."]
                              error-message)]
        (html
         (if-not error-message
           [:span]

           [:div.flash-error-wrapper.row-fluid
            [:div.offset1.span10
             [:div.alert.alert-block.alert-danger
              [:a.close {:on-click #(put! controls-ch [:clear-error-message-clicked])} "×"]
              "Error: " display-message
              " If we can help, " (contact-us-inner controls-ch) "."]]]))))))

(defn normalize-html
  "Creates a valid html string given a (possibly) invalid html string."
  [html-string]
  (let [dom-helper (goog.dom.DomHelper.)]
    (->> html-string
         (.htmlToDocumentFragment dom-helper)
         (.getOuterHtml dom-helper))))

(defn messages [messages]
  [:div.row-fluid
   (when (pos? (count messages))
     (let [dom-helper (goog.dom.DomHelper.)]
       [:div#build-messages.offset1.span10
        (map (fn [message]
               [:div.alert.alert-info
                [:strong "Warning: "]
                [:span {:dangerouslySetInnerHTML #js {"__html" (normalize-html (:message message))}}]])
             messages)]))])

(def icon-paths
  {:logomark-precursor-stroke "M35.7,45.3V95 M60.5,84.4C82,78.6,94.8,56.5,89,34.9C83.2,13.4,61.1,0.6,39.5,6.4S5.2,34.3,11,55.9"
   :download-stroke "M5,95 h90 M5,85v10 M95,85v10 M50,5v70 M50,75l30-30 M20,45l30,30"
   :dot-stroke "M49.5,50a0.5,0.5 0 1,0 1,0a0.5,0.5 0 1,0 -1,0"
   :cog-stroke "M94.8,54.3c-0.3,2.1-1.9,3.8-3.9,4c-2.5,0.3-7.7,0.9-7.7,0.9c-2.3,0.5-3.9,2.5-3.9,4.9c0,1,0.3,2,0.8,2.7c0,0.1,3.1,4.1,4.7,6.2 c1.3,1.6,1.2,3.9-0.1,5.5c-1.8,2.3-3.8,4.3-6.1,6.1c-0.8,0.7-1.8,1-2.8,1c-0.9,0-2-0.3-2.7-0.9L67,80.1c-0.7-0.6-1.8-0.8-2.8-0.8 c-2.4,0-4.4,1.8-4.9,4.1l-0.9,7.5c-0.3,2.1-2,3.7-4,3.9C52.9,94.9,51.4,95,50,95c-1.4,0-2.9-0.1-4.3-0.2c-2.1-0.3-3.7-1.9-4-3.9 c0,0-0.9-7.4-0.9-7.5c-0.4-2.3-2.4-4.1-4.9-4.1c-1.1,0-2.2,0.4-3,0.9L27,84.8c-0.7,0.7-1.8,0.9-2.7,0.9c-1,0-2-0.4-2.8-1 c-2.3-1.8-4.3-3.8-6.1-6.1c-1.3-1.6-1.4-3.9-0.1-5.5l4.5-5.9c0.7-0.8,1-1.9,1-3c0-2.5-1.9-4.6-4.3-4.9l-7.3-0.9 c-2.1-0.3-3.7-2-3.9-4c-0.3-2.8-0.3-5.7,0-8.6c0.2-2.1,1.9-3.7,3.9-4l7.3-0.9c2.4-0.4,4.3-2.4,4.3-5c0-1-0.4-2.1-1-2.9 c0,0-3-3.9-4.5-5.9c-1.3-1.6-1.3-3.9,0.1-5.5c1.8-2.3,3.8-4.3,6.1-6.1c1.6-1.3,3.9-1.4,5.5-0.1l5.9,4.6c0.8,0.6,1.9,0.9,3,0.9 c2.4,0,4.5-1.8,4.9-4.1l0.9-7.5c0.3-2.1,2-3.7,4-3.9c2.8-0.3,5.7-0.3,8.6,0c2.1,0.3,3.7,1.9,4,3.9l0.9,7.5c0.5,2.3,2.4,4.1,4.9,4.1 c1,0,2-0.4,2.8-0.8c0,0,4-3.1,6.1-4.7c1.6-1.3,3.9-1.2,5.5,0.1c2.3,1.8,4.3,3.8,6.1,6.1c1.3,1.6,1.4,3.9,0.1,5.5 c0,0-4.7,6.1-4.7,6.2c-0.6,0.7-0.8,1.7-0.8,2.6c0,2.4,1.7,4.4,3.9,5c0,0,5.2,0.7,7.7,0.9c2.1,0.3,3.7,2,3.9,4 C95.1,48.5,95.1,51.4,94.8,54.3z"})

(def icon-templates
  {:logomark-precursor {:paths [:logomark-precursor-stroke]}
   :download {:paths [:download-stroke]}
   :settings {:paths [:dot-stroke :cog-stroke]}
   :logo {:paths [:turn :circle]}
   :pass {:paths [:turn :check]}
   :fail {:paths [:turn :times]}
   :queued {:paths [:turn :clock]}
   :logo-light {:paths [:slim_turn :slim_circle]}
   :busy-light {:paths [:slim_turn :slim_circle]}
   :pass-light {:paths [:slim_check]}
   :fail-light {:paths [:slim_times]}
   :hold-light {:paths [:slim_clock]}
   :stop-light {:paths [:slim_ban]}
   :settings-light {:paths [:slim_settings :slim_circle]}
   :none-light {:paths [:slim_circle]}
   :repo {:paths [:repo]}
   :spinner {:paths [:turn :circle]}})

(defn icon [icon-name]
  (let [template (get icon-templates icon-name)]
    [:i {:class (str "icon-" (name icon-name))}
      [:svg {:viewBox "0 0 100 100"}
       (for [path (:paths template)]
        [:path {:class (name path) :d (get icon-paths path)}])]]))

(def spinner
  (icon :logomark-precursor))

(defn updating-duration
  "Takes a :start time string and :stop time string. Updates the component every second
   if the stop-time is nil.
   By default, uses datetime/as-duration, but can also take a custom :formatter
   function in opts."
  [{:keys [start stop]} owner opts]
  (reify
    om/IDisplayName (display-name [_] "Updating Duration")
    om/IInitState
    (init-state [_]
      {:watcher-uuid (utils/uuid)
       :now (datetime/server-now)
       :has-watcher? false})
    om/IDidMount
    (did-mount [_]
      (when-not stop
        (let [timer-atom (om/get-shared owner [:timer-atom])
              uuid (om/get-state owner [:watcher-uuid])]
          (add-watch timer-atom uuid (fn [_k _r _p t]
                                       (om/set-state! owner [:now] t)))
          (om/set-state! owner [:has-watcher?] true))))
    om/IWillUnmount
    (will-unmount [_]
      (when (om/get-state owner [:has-watcher?])
        (remove-watch (om/get-shared owner [:timer-atom])
                      (om/get-state owner [:watcher-uuid]))))

    om/IDidUpdate
    (did-update [_ _ _]
      (when (and stop (om/get-state owner [:has-watcher?]))
        (remove-watch (om/get-shared owner [:timer-atom])
                      (om/get-state owner [:watcher-uuid]))))
    om/IRenderState
    (render-state [_ {:keys [now]}]
      (let [end-ms (if stop
                     (.getTime (js/Date. stop))
                     now)
            formatter (get opts :formatter datetime/as-duration)
            duration-ms (- end-ms (.getTime (js/Date. start)))]
        (dom/span nil (formatter duration-ms))))))
