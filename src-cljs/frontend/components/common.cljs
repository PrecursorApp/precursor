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
  {:logomark-precursor-stroke "M35.7,45.3V95 M60.5,84.4C82,78.6,94.8,56.5,89,34.9C83.2,13.4,61.1,0.6,39.5,6.4S5.2,34.3,11,55.9"})

(def icon-templates
  {:logomark-precursor {:paths [:logomark-precursor-stroke]}
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
