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

;; TODO: Why do we have ico and icon?
(def ico-paths
  {:turn "M50,0C26.703,0,7.127,15.936,1.576,37.5c-0.049,0.191-0.084,0.389-0.084,0.595c0,1.315,1.066,2.381,2.381,2.381h20.16c0.96,0,1.783-0.572,2.159-1.391c0,0,0.03-0.058,0.041-0.083C30.391,30.033,39.465,23.809,50,23.809c14.464,0,26.19,11.726,26.19,26.19c0,14.465-11.726,26.19-26.19,26.19c-10.535,0-19.609-6.225-23.767-15.192c-0.011-0.026-0.041-0.082-0.041-0.082c-0.376-0.82-1.199-1.392-2.16-1.392H3.874c-1.315,0-2.381,1.066-2.381,2.38c0,0.206,0.035,0.406,0.084,0.597C7.127,84.063,26.703,100,50,100c27.614,0,50-22.387,50-50C100,22.385,77.614,0,50,0z"
   :slim_turn "M7.5,65c6.2,17.5,22.8,30,42.4,30c24.9,0,45-20.1,45-45c0-24.9-20.1-45-45-45C30.3,5,13.7,17.5,7.5,35"
   :circle "M38.096000000000004,50a11.904,11.904 0 1,0 23.808,0a11.904,11.904 0 1,0 -23.808,0"
   :check "M65.151,44.949L51.684,58.417l-3.367,3.367c-0.93,0.93-2.438,0.93-3.367,0l-3.368-3.367l-6.734-6.733 c-0.93-0.931-0.93-2.438,0-3.368l3.368-3.367c0.929-0.93,2.437-0.93,3.367,0L46.633,50l11.785-11.785 c0.931-0.929,2.438-0.929,3.367,0l3.366,3.367C66.082,42.511,66.082,44.019,65.151,44.949z"
   :times "M61.785,55.051L56.734,50l5.051-5.05c0.93-0.93,0.93-2.438,0-3.368l-3.367-3.367c-0.93-0.929-2.438-0.929-3.367,0L50,43.265l-5.051-5.051c-0.93-0.929-2.437-0.929-3.367,0l-3.367,3.367c-0.93,0.93-0.93,2.438,0,3.368l5.05,5.05l-5.05,5.051c-0.93,0.929-0.93,2.438,0,3.366l3.367,3.367c0.93,0.93,2.438,0.93,3.367,0L50,56.734l5.05,5.05c0.93,0.93,2.438,0.93,3.367,0l3.367-3.367C62.715,57.488,62.715,55.979,61.785,55.051z"
   :slim_circle "M49.5,50a0.5,0.5 0 1,0 1,0a0.5,0.5 0 1,0 -1,0"
   :slim_check "M35,80 L5,50 M95,20L35,80"
   :slim_times "M82.5,82.5l-65-65 M82.5,17.5l-65,65"
   :slim_clock "M7.5,35C13.7,17.5,30.3,5,49.9,5c24.9,0,45,20.1,45,45c0,24.9-20.1,45-45,45C30.3,95,13.7,82.5,7.5,65 M50,20v30 M50,50h20"
   :slim_ban "M95,50 c0,24.9-20.1,45-45,45S5,74.9,5,50S25.1,5,50,5S95,25.1,95,50z M18.2,81.8l63.6-63.6"
   :slim_settings "M94.8,54.3c-0.3,2.1-1.9,3.8-3.9,4c-2.5,0.3-7.7,0.9-7.7,0.9c-2.3,0.5-3.9,2.5-3.9,4.9c0,1,0.3,2,0.8,2.7c0,0.1,3.1,4.1,4.7,6.2 c1.3,1.6,1.2,3.9-0.1,5.5c-1.8,2.3-3.8,4.3-6.1,6.1c-0.8,0.7-1.8,1-2.8,1c-0.9,0-2-0.3-2.7-0.9L67,80.1c-0.7-0.6-1.8-0.8-2.8-0.8 c-2.4,0-4.4,1.8-4.9,4.1l-0.9,7.5c-0.3,2.1-2,3.7-4,3.9C52.9,94.9,51.4,95,50,95c-1.4,0-2.9-0.1-4.3-0.2c-2.1-0.3-3.7-1.9-4-3.9 c0,0-0.9-7.4-0.9-7.5c-0.4-2.3-2.4-4.1-4.9-4.1c-1.1,0-2.2,0.4-3,0.9L27,84.8c-0.7,0.7-1.8,0.9-2.7,0.9c-1,0-2-0.4-2.8-1 c-2.3-1.8-4.3-3.8-6.1-6.1c-1.3-1.6-1.4-3.9-0.1-5.5l4.5-5.9c0.7-0.8,1-1.9,1-3c0-2.5-1.9-4.6-4.3-4.9l-7.3-0.9 c-2.1-0.3-3.7-2-3.9-4c-0.3-2.8-0.3-5.7,0-8.6c0.2-2.1,1.9-3.7,3.9-4l7.3-0.9c2.4-0.4,4.3-2.4,4.3-5c0-1-0.4-2.1-1-2.9 c0,0-3-3.9-4.5-5.9c-1.3-1.6-1.3-3.9,0.1-5.5c1.8-2.3,3.8-4.3,6.1-6.1c1.6-1.3,3.9-1.4,5.5-0.1l5.9,4.6c0.8,0.6,1.9,0.9,3,0.9 c2.4,0,4.5-1.8,4.9-4.1l0.9-7.5c0.3-2.1,2-3.7,4-3.9c2.8-0.3,5.7-0.3,8.6,0c2.1,0.3,3.7,1.9,4,3.9l0.9,7.5c0.5,2.3,2.4,4.1,4.9,4.1 c1,0,2-0.4,2.8-0.8c0,0,4-3.1,6.1-4.7c1.6-1.3,3.9-1.2,5.5,0.1c2.3,1.8,4.3,3.8,6.1,6.1c1.3,1.6,1.4,3.9,0.1,5.5 c0,0-4.7,6.1-4.7,6.2c-0.6,0.7-0.8,1.7-0.8,2.6c0,2.4,1.7,4.4,3.9,5c0,0,5.2,0.7,7.7,0.9c2.1,0.3,3.7,2,3.9,4 C95.1,48.5,95.1,51.4,94.8,54.3z"
   :clock "M59.524,47.619h-7.143V30.952c0-1.315-1.066-2.381-2.381-2.381c-1.315,0-2.381,1.065-2.381,2.381V50c0,1.315,1.066,2.38,2.381,2.38h9.524c1.314,0,2.381-1.065,2.381-2.38S60.839,47.619,59.524,47.619z"
   :repo "M44.4,27.5h-5.6v5.6h5.6V27.5z M44.4,16.2h-5.6v5.6h5.6V16.2z M78.1,5H21.9c0,0-5.6,0-5.6,5.6 v67.5c0,5.6,5.6,5.6,5.6,5.6h11.2V95l8.4-8.4L50,95V83.8h28.1c0,0,5.6-0.1,5.6-5.6V10.6C83.8,5,78.1,5,78.1,5z M78.1,72.5 c0,5.4-5.6,5.6-5.6,5.6H50v-5.6H33.1v5.6h-5.6c-5.6,0-5.6-5.6-5.6-5.6v-5.6h56.2V72.5z M78.1,61.2h-45V10.6h45.1L78.1,61.2z M44.4,50h-5.6v5.6h5.6V50z M44.4,38.8h-5.6v5.6h5.6V38.8z"})

(def ico-templates
  {:logo {:paths [:turn :circle]}
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

(defn ico [ico-name]
  (let [template (get ico-templates ico-name)]
    [:i {:class "ico"}
      [:svg {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 100 100"
             :class (name ico-name)
             :dangerouslySetInnerHTML
             #js {"__html" (apply str
                                  (for [path (:paths template)]
                                    (str "<path class='" (name path) "' fill='none' d='" (get ico-paths path) "'></path>")))}}]]))

(def spinner
  (ico :spinner))

;; TODO: why do we have ico and icon?
(def icon-shapes
  {:turn {:path "M50,0C26.703,0,7.127,15.936,1.576,37.5c-0.049,0.191-0.084,0.389-0.084,0.595c0,1.315,1.066,2.381,2.381,2.381h20.16c0.96,0,1.783-0.572,2.159-1.391c0,0,0.03-0.058,0.041-0.083C30.391,30.033,39.465,23.809,50,23.809c14.464,0,26.19,11.726,26.19,26.19c0,14.465-11.726,26.19-26.19,26.19c-10.535,0-19.609-6.225-23.767-15.192c-0.011-0.026-0.041-0.082-0.041-0.082c-0.376-0.82-1.199-1.392-2.16-1.392H3.874c-1.315,0-2.381,1.066-2.381,2.38c0,0.206,0.035,0.406,0.084,0.597C7.127,84.063,26.703,100,50,100c27.614,0,50-22.387,50-50C100,22.385,77.614,0,50,0z"}
   :circle {:path "" :cx "50" :cy "50" :r "11.904"}
   :pass {:path "M65.151,44.949L51.684,58.417l-3.367,3.367c-0.93,0.93-2.438,0.93-3.367,0l-3.368-3.367l-6.734-6.733 c-0.93-0.931-0.93-2.438,0-3.368l3.368-3.367c0.929-0.93,2.437-0.93,3.367,0L46.633,50l11.785-11.785 c0.931-0.929,2.438-0.929,3.367,0l3.366,3.367C66.082,42.511,66.082,44.019,65.151,44.949z"}
   :fail {:path "M61.785,55.051L56.734,50l5.051-5.05c0.93-0.93,0.93-2.438,0-3.368l-3.367-3.367c-0.93-0.929-2.438-0.929-3.367,0L50,43.265l-5.051-5.051c-0.93-0.929-2.437-0.929-3.367,0l-3.367,3.367c-0.93,0.93-0.93,2.438,0,3.368l5.05,5.05l-5.05,5.051c-0.93,0.929-0.93,2.438,0,3.366l3.367,3.367c0.93,0.93,2.438,0.93,3.367,0L50,56.734l5.05,5.05c0.93,0.93,2.438,0.93,3.367,0l3.367-3.367C62.715,57.488,62.715,55.979,61.785,55.051z"}
   :clock {:path "M59.524,47.619h-7.143V30.952c0-1.315-1.066-2.381-2.381-2.381c-1.315,0-2.381,1.065-2.381,2.381V50c0,1.315,1.066,2.38,2.381,2.38h9.524c1.314,0,2.381-1.065,2.381-2.38S60.839,47.619,59.524,47.619z"}})

(defn icon [{icon-type :type icon-name :name}]
  [:svg {:class (str "icon-" (name icon-name))
         :xmlns "http://www.w3.org/2000/svg"
         :viewBox "0 0 100 100"
         :dangerouslySetInnerHTML
         #js {"__html" (apply str (concat
                                   (when (= :status icon-type)
                                     [(str "<path class='" (name icon-name) "' fill='none'"
                                           " d='" (get-in icon-shapes [:turn :path]) "'></path>")])
                                   [(str "<path class='" (name icon-name) "' fill='none'"
                                         " d='" (get-in icon-shapes [icon-name :path]) "'></path>")]))}}])

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
