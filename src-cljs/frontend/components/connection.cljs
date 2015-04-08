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
           [:h3.make "Server Connection"]
           [:p.make
            "Debug information about the persistent connection to Precursor's backend service."
            [:table.connection-items
             [:tbody
              [:tr.make
               [:th {:colSpan 2}
                "Socket"]]
              [:tr.make
               [:td [:div "type"]]
               [:td [:div.connection-result (str (:type sente-state))]]]
              [:tr.make
               [:td [:div "open?"]]
               [:td [:div.connection-result (str (:open? sente-state))]]]
              [:tr.make
               [:td [:div "destroyed?"]]
               [:td [:div.connection-result (str (:destroyed? sente-state))]]]
              [:tr.make
               [:td [:div "Last message"]]
               [:td [:div.connection-result (some->> sente-state
                                             :last-message
                                             :time
                                             (goog.date.DateTime.)
                                             (time-format/unparse (time-format/formatter "M/d/yyyy h:mm:ssa")))]]]]]]
           [:h3.make "WebRTC Connection"]
           [:p.make
            "Debug information about the connection between your browser and your collaborator's browsers. This connection isn't always present."]

           (for [conn-stats (:connections rtc-stats)
                 :let [conn-id (str (:producer conn-stats)
                                    (:consumer conn-stats))]]
             [:div {:key conn-id}
              [:h4.make {:style {:cursor "pointer"}
                         :key "header"
                         :on-click #(om/update-state! owner :hidden-stats
                                                      (fn [s]
                                                        (if (contains? s conn-id)
                                                          (disj s conn-id)
                                                          (conj (or s #{}) conn-id))))}
               "Connection from " (client-id->user app (:producer conn-stats))
               " to " (client-id->user app (:consumer conn-stats)) "."]
              (when-not (contains? (om/get-state owner :hidden-stats) conn-id)
                [:div
                 [:table.connection-items.make {:key "conn"}
                  [:tbody
                   [:tr.make
                    [:th {:colSpan 2}
                     "Peer connection"]]
                   [:tr.make
                    [:td [:div "connection state"]]
                    [:td [:div.connection-result (:connection-state conn-stats)]]]
                   [:tr.make
                    [:td [:div "gathering state"]]
                    [:td [:div.connection-result (:gathering-state conn-stats)]]]
                   [:tr.make
                    [:td [:div "signaling state"]]
                    [:td [:div.connection-result (:signaling-state conn-stats)]]]]]
                 (when-let [track (some-> (or (some-> conn-stats :local-streams first)
                                              (some-> conn-stats :remote-streams first))
                                    :tracks
                                    first)]
                   [:table.connection-items.make {:key "stream-stats"}
                    [:tbody
                     [:tr.make
                      [:th {:colSpan 2}
                       "Stream"]]
                     [:tr.make
                      [:td [:div "enabled"]]
                      [:td [:div.connection-result (str (:enabled track))]]]
                     [:tr.make
                      [:td [:div "kind"]]
                      [:td [:div.connection-result (:kind track)]]]
                     [:tr.make
                      [:td [:div "label"]]
                      [:td [:div.connection-result {:title (:label track)} (gstring/truncate (:label track) 30)]]]]])
                 (for [[stat stat-map] (reverse (:stats conn-stats))]
                   [:table.connection-items.make {:key stat}
                    [:tbody
                     [:tr.make
                      [:th {:colSpan 2}
                       [:div stat]]]
                     (for [[k v] stat-map
                           :let [v (str v)]]
                       [:tr.make
                        [:td [:div {:title k} (gstring/truncate k 15)]]
                        [:td [:div.connection-result {:title v}
                              (gstring/truncate v 20)]]])]])])])]])))))
