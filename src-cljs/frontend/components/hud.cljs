(ns frontend.components.hud
  (:require [datascript :as d]
            [frontend.components.common :as common]
            [frontend.models.chat :as chat-model]
            [frontend.state :as state]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]])
  (:import [goog.ui IdGenerator]))

(def tools-templates
  {:circle {:type "ellipse"
            :path "M128,0v128l110.9-64C216.7,25.7,175.4,0,128,0z"
            :hint "Ellipse Tool (L)"
            :icon :ellipse-stroke}
   :rect   {:type "rectangle"
            :path "M238.9,192c10.9-18.8,17.1-40.7,17.1-64s-6.2-45.2-17.1-64 L128,128L238.9,192z"
            :hint "Rectangle Tool (M)"
            :icon :rectangle-stroke}
   :line   {:type "line"
            :path "M238.9,192L128,128v128C175.4,256,216.7,230.3,238.9,192z"
            :hint "Line Tool (\\)"
            :icon :line-stroke}
   :pen    {:type "pencil"
            :path "M17.1,192c22.1,38.3,63.5,64,110.9,64V128L17.1,192z"
            :hint "Pencil Tool (N)"
            :icon :pencil-stroke}
   :text   {:type "text"
            :path "M17.1,64C6.2,82.8,0,104.7,0,128s6.2,45.2,17.1,64L128,128 L17.1,64z"
            :hint "Text Tool (T)"
            :icon :text-stroke}
   :select {:type "select"
            :path "M128,0C80.6,0,39.3,25.7,17.1,64L128,128V0z"
            :hint "Select Tool (V)"
            :icon :cursor-stroke}})

(defn chat-button [app owner]
  (reify
    om/IInitState
    (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (d/listen! (om/get-shared owner :db)
                 (om/get-state owner :listener-key)
                 (fn [tx-report]
                   ;; TODO: better way to check if state changed
                   (when-let [chat-datoms (seq (filter #(= :chat/body (:a %)) (:tx-data tx-report)))]
                     (om/refresh! owner)))))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :db) (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)
            chat-opened? (get-in app state/chat-opened-path)
            chat-button-learned? (get-in app state/chat-button-learned-path)
            last-read-time (get-in app (state/last-read-chat-time-path (:document/id app)))
            unread-chat-count (chat-model/compute-unread-chat-count @db last-read-time)
            unread-chat-count (if last-read-time
                                unread-chat-count
                                ;; add one for the dummy message
                                (inc unread-chat-count))]
        (html
          [:a.chat-button {:on-click #(cast! :chat-toggled)
                           :role "button"
                           :data-left (when-not chat-button-learned?
                                        (if chat-opened? "Close Chat" "Open Chat"))
                           :title (when chat-button-learned?
                                    (if chat-opened? "Close Chat" "Open Chat"))}
           (when (and (not chat-opened?) (pos? unread-chat-count))
             [:i.unseen-eids (str unread-chat-count)])
           (common/icon :chat)])))))

(defn mouse-stats [app owner]
  (reify
    om/IRender
    (render [_]
      (html
        [:div.mouse-stats
         (pr-str (select-keys (:mouse app) [:x :y :rx :ry]))]))))

(defn radial-hint [app owner]
  (reify
    om/IRender
    (render [_]
      (html
        [:div.radial-hint {:style {:top  (+ (get-in app [:mouse :y]) 16)
                                   :left (+ (get-in app [:mouse :x]) 16)}}
         (if (= :touch (get-in app [:mouse :type]))
           "Tap and hold to select tool"
           "Right-click.")]))))

(defn info-button [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            info-button-learned? (get-in app state/info-button-learned-path)]
        (html
          [:a.info-button {:on-click #(cast! :overlay-info-toggled)
                           :role "button"
                           :class (when-not info-button-learned? "hover")
                           :data-right (when-not info-button-learned? "What is Precursor?")
                           :title (when info-button-learned? "What is Precursor?")}
           (common/icon :info)])))))

(defn radial-menu [app owner]
  (reify
    om/IRender
    (render [_]
      (let [{:keys [cast! handlers]} (om/get-shared owner)]
        (html
          [:a.radial-menu {:style {:top  (- (get-in app [:menu :y]) 128)
                                     :left (- (get-in app [:menu :x]) 128)}}
           [:svg.radial-buttons {:width "256" :height "256"}
            (for [[tool template] tools-templates]
              [:g.radial-button {:class (str "tool-" (:type template))}
               [:title (:hint template)]
               [:path.radial-pie {:d (:path template)
                                  :key tool
                                  :on-mouse-up #(do (cast! :tool-selected [tool]))
                                  :on-touch-end #(do (cast! :tool-selected [tool]))}]
               [:path.radial-icon {:class (str "shape-" (:type template))
                                   :d (get common/icon-paths (:icon template))}]])
            [:circle.radial-point {:cx "128" :cy "128" :r "4"}]]])))))

(defn hud [app owner]
  (reify
    om/IRender
    (render [_]
      (html
        (let [right-click-learned? (get-in app state/right-click-learned-path)]
         [:div.app-hud
          (om/build chat-button app)
          (when (and (:mouse app) (not= :touch (:type (:mouse app))))
            (om/build mouse-stats app))
          (when (and (not right-click-learned?) (:mouse app))
            (om/build radial-hint app))
          (when-not (:cust app)
            (om/build info-button app))
          (when (get-in app [:menu :open?])
            (om/build radial-menu app))
          ])))))

