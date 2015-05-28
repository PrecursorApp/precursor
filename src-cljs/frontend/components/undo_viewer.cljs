(ns frontend.components.undo-viewer
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [cljs-time.core :as time]
            [datascript :as d]
            [frontend.async :refer [put!]]
            [frontend.auth :as auth]
            [frontend.clipboard :as clipboard]
            [frontend.components.common :as common]
            [frontend.datascript :as ds]
            [frontend.datetime :as datetime]
            [frontend.db :as fdb]
            [frontend.models.layer :as layer-model]
            [frontend.state :as state]
            [frontend.urls :as urls]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.date :refer (date->bucket)]
            [goog.date]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [sablono.core :refer (html)])
  (:import [goog.ui IdGenerator]))

(defn undos-viewer [app owner]
  (reify
    om/IDisplayName (display-name [_] "Undo Viewer")
    om/IDidMount
    (did-mount [_]
      (add-watch (:undo-state app) :undos-viewer (fn [& args] (om/refresh! owner))))
    om/IWillUnmount
    (will-unmount [_]
      (remove-watch (:undo-state app) :undos-viewer))
    om/IRender
    (render [_]
      (let [undo-state @(:undo-state app)
            last-undo (:last-undo undo-state)]
        (html
         [:section.menu-view
          [:div.make
           (for [transaction (:transactions undo-state)]
             [:div {:style {:padding "10px"}}
              [:img {:style {:border (if (= last-undo transaction)
                                       "2px solid red"
                                       "1px solid gray")}
                     :src (str "data:image/svg+xml;utf8," (utils/inspect (clipboard/render-layers {:layers (layer-model/all (:db-after transaction))})))}]])]])))))
