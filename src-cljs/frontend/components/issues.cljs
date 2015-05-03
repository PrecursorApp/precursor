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

(defn issues [app owner]
  (reify
    om/IInitState
    (init-state [_] {:watch-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (sente/subscribe-to-issues (:sente app)
                                 (om/get-shared owner :comms)
                                 (om/get-shared owner :issue-db))
      (fdb/add-attribute-listener (om/get-shared owner :issue-db)
                                  :issue/frontend-id
                                  (om/get-state owner :listener-key)
                                  (fn [tx-report]
                                    (om/refresh! owner))))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-attribute-listener (om/get-shared owner :issue-db)
                                     :issue/frontend-id
                                     (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! issue-db]} (om/get-shared owner)
            issue-ids (map :e (d/datoms @issue-db :aevt :issue/frontend-id))]
        (html
         [:div.menu-view
          [:div.content.make
           "Ids: " (pr-str issue-ids)]])))))
