(ns frontend.components.issues
  (:require [cljs-time.format :as time-format]
            [clojure.string :as str]
            [datascript :as d]
            [frontend.components.common :as common]
            [frontend.datascript :as ds]
            [frontend.datetime :as datetime]
            [frontend.db :as fdb]
            [frontend.sente :as sente]
            [frontend.urls :as urls]
            [frontend.utils :as utils]
            [goog.string :as gstring]
            [goog.string.format]
            [om.core :as om]
            [om.dom :as dom])
  (:require-macros [sablono.core :refer (html)])
  (:import [goog.ui IdGenerator]))

(defn issue-form [_ owner {:keys [issue-db]}]
  (reify
    om/IInitState (init-state [_] {:issue-title ""})
    om/IRenderState
    (render-state [_ {:keys [issue-title]}]
      (html
       [:form {:on-submit #(do (utils/stop-event %)
                               (when (seq issue-title)
                                 (d/transact! issue-db [{:db/id -1
                                                         :issue/title issue-title
                                                         :frontend/issue-id (utils/squuid)}])
                                 (om/set-state! owner :issue-title "")))}
        [:input {:type "text"
                 :value issue-title
                 :onChange #(om/set-state! owner :issue-title (.. % -target -value))}]]))))

(defn issue [{:keys [issue-id]} owner {:keys [issue-db]}]
  (reify
    om/IInitState
    (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (fdb/add-entity-listener issue-db
                               issue-id
                               (om/get-state owner :listener-key)
                               (fn [tx-report]
                                 (om/refresh! owner))))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-entity-listener issue-db
                                  issue-id
                                  (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [issue (d/entity @issue-db issue-id)]
        (html
         [:div
          (:issue/title issue)
          " "
          [:span {:on-click #(d/transact! issue-db [[:db.fn/retractEntity issue-id]])
                  :style {:cursor "pointer"}}
           "X"]])))))

(defn issues [app owner]
  (reify
    om/IInitState
    (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (def _issue-db (om/get-shared owner :issue-db))
      (fdb/setup-issue-listener!
       (om/get-shared owner :issue-db)
       (om/get-state owner :listener-key)
       (om/get-shared owner :comms)
       (om/get-shared owner :sente))
      (fdb/add-attribute-listener (om/get-shared owner :issue-db)
                                  :frontend/issue-id
                                  (om/get-state owner :listener-key)
                                  (fn [tx-report]
                                    (om/refresh! owner)))
      (sente/subscribe-to-issues (:sente app)
                                 (om/get-shared owner :comms)
                                 (om/get-shared owner :issue-db)))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :issue-db) (om/get-state owner :listener-key))
      (fdb/remove-attribute-listener (om/get-shared owner :issue-db)
                                     :frontend/issue-id
                                     (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! issue-db]} (om/get-shared owner)
            issue-ids (map :e (d/datoms @issue-db :aevt :issue/title))]
        (html
         [:div.menu-view
          [:div.content.make
           [:div.make
            (om/build issue-form {} {:opts {:issue-db issue-db}})]
           [:div.make
            (when (seq issue-ids)
              (om/build-all issue (map (fn [i] {:issue-id i}) (sort issue-ids))
                            {:key :issue-id
                             :opts {:issue-db issue-db}}))]]])))))
