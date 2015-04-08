(ns frontend.components.connection
  (:require [cljs-time.format :as time-format]
            [frontend.components.inspector :as inspector]
            [frontend.rtc :as rtc]
            [frontend.utils :as utils]
            [goog.date]
            [goog.string :as gstring]
            [om.core :as om]
            [om.dom :as dom])
  (:require-macros [sablono.core :refer (html)]))

(defn client-id->user [app client-id]
  (if (= client-id (:client-id app))
    "you"
    (let [cust-uuid (get-in app [:subscribers :info client-id :cust/uuid])]
      (get-in app [:cust-data :uuid->cust cust-uuid :cust/name] (apply str (take 6 client-id))))))

(defn connection-info [app owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (add-watch (get-in app [:sente :state])
                 ::connection-info
                 (fn [_ _ _ _]
                   (om/refresh! owner)))
      (add-watch rtc/conns
                 ::connection-info
                 (fn [_ _ _ _]
                   (om/refresh! owner))))
    om/IWillUnmount
    (will-unmount [_]
      (remove-watch (get-in app [:sente :state]) ::connection-info)
      (remove-watch rtc/conns ::connection-info))
    om/IRender
    (render [_]
      (let [sente-state @(get-in app [:sente :state])
            rtc-stats (rtc/gather-stats)
            uuid->cust (get-in app [:cust-data :uuid->cust])]
        (html
         [:div.menu-view
          [:div.content
           [:h4.make "Server Connection"]
           [:p.make
            "Debug information about the persistent connection to Precursor's backend service."
            [:table.shortcuts-items
             [:tbody
              [:tr.make
               [:td [:div "type"]]
               [:td [:div.shortcuts-result (str (:type sente-state))]]]
              [:tr.make
               [:td [:div "open?"]]
               [:td [:div.shortcuts-result (str (:open? sente-state))]]]
              [:tr.make
               [:td [:div "destroyed?"]]
               [:td [:div.shortcuts-result (str (:destroyed? sente-state))]]]
              [:tr.make
               [:td [:div "Last message"]]
               [:td [:div.shortcuts-result (some->> sente-state
                                             :last-message
                                             :time
                                             (goog.date.DateTime.)
                                             (time-format/unparse (time-format/formatter "M/d/yyyy h:mm:ssa")))]]]]]]
           [:h4.make "WebRTC Connection"]
           [:p.make
            "Debug information about the connection between your browser and your collaborator's browsers. This connection isn't always present."
            (for [conn-stats (:connections rtc-stats)]
              (list
               [:h5.make
                "Connection from " (client-id->user app (:producer conn-stats))
                " to " (client-id->user app (:consumer conn-stats)) "."]
               [:p.make
                [:table.shortcuts-items.make
                 [:tbody
                  [:tr.make
                   [:td [:div "connection state"]]
                   [:td [:div.shortcuts-result (:connection-state conn-stats)]]]
                  [:tr.make
                   [:td [:div "gathering state"]]
                   [:td [:div.shortcuts-result (:gathering-state conn-stats)]]]
                  [:tr.make
                   [:td [:div "signaling state"]]
                   [:td [:div.shortcuts-result (:signaling-state conn-stats)]]]]]
                (when-let [track (some-> (or (some-> conn-stats :local-streams first)
                                             (some-> conn-stats :remote-streams first))
                                   :tracks
                                   first)]
                  (list
                   [:p.make
                    "Stream:"
                    [:table.shortcuts-items.make
                     [:tbody
                      [:tr.make
                       [:td [:div "enabled"]]
                       [:td [:div.shortcuts-result (str (:enabled track))]]]
                      [:tr.make
                       [:td [:div "kind"]]
                       [:td [:div.shortcuts-result (:kind track)]]]
                      [:tr.make
                       [:td [:div "label"]]
                       [:td [:div.shortcuts-result {:title (:label track)} (gstring/truncate (:label track) 30)]]]]]]))
                (for [[stat stat-map] (reverse (:stats conn-stats))]
                  (list
                   [:p.make
                    [:table.shortcuts-items.make
                     [:tbody
                      [:tr.make
                       [:td "Stat Name"]
                       [:td stat]]
                      (for [[k v] stat-map
                            :let [v (str v)]]
                        [:tr.make
                         [:td [:div {:title k} (gstring/truncate k 15)]]
                         [:td [:div.shortcuts-result {:title v}
                               (gstring/truncate v 20)]]])]]]))]))]]])))))
