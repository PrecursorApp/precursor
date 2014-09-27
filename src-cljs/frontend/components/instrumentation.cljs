(ns frontend.components.instrumentation
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [frontend.async :refer [put!]]
            [frontend.components.common :as common]
            [frontend.env :as env]
            [frontend.models.project :as project-model]
            [frontend.routes :as routes]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.github :refer [auth-url]]
            [frontend.utils.vcs-url :as vcs-url]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]]))

(defn line-items [instrumentation-data owner]
  (reify
    om/IDisplayName (display-name [_] "Instrumentation Line Items")
    om/IRender
    (render [_]
      (html
       [:div.admin-stats
        [:table
         [:thead
          [:tr
           [:th "url"]
           [:th.number "client latency"]
           [:th.number "server latency"]
           [:th.number "mongo queries"]
           [:th.number "total query latency"]]]
         [:tbody
          (for [item instrumentation-data]
            [:tr
             [:td (:url item)]
             [:td.number (:request-time item)]
             [:td.number (:circle-latency item)]
             [:td.number (:query-count item)]
             [:td.number (:query-latency item)]])]]]))))

(defn summary [instrumentation-data owner]
  (reify
    om/IDisplayName (display-name [_] "Instrumentation Summary")
    om/IRender
    (render [_]
      (let [controls-ch (om/get-shared owner [:comms :controls])
            sums (reduce (fn [acc item]
                           (merge-with + acc (select-keys item [:request-time :circle-latency :query-latency :query-count])))
                         {} instrumentation-data)]
        (html
         [:div.metrics {:on-click #(put! controls-ch [:instrumentation-line-items-toggled])}
          [:span.data (str (count instrumentation-data))]
          [:strong "requests"]
          [:span.data (:request-time sums) "ms"]
          [:strong "client"]
          [:span.data (:circle-latency sums) "ms"]
          [:strong "server"]
          [:span.data (:query-latency sums) "ms / " (:query-count sums) " queries"]
          [:strong "mongo"]])))))
