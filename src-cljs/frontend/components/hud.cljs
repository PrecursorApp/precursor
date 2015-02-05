(ns frontend.components.hud
  (:require [datascript :as d]
            [frontend.components.common :as common]
            [frontend.models.chat :as chat-model]
            [frontend.overlay :refer [current-overlay overlay-visible? overlay-count]]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]])
  (:import [goog.ui IdGenerator]))

(def tools-templates
  {:circle {:type "ellipse"
            :path "M128,0v128l110.9-64C216.7,25.7,175.4,0,128,0z"
            :hint "Ellipse Tool (L)"
            :icon :stroke-ellipse}
   :rect   {:type "rectangle"
            :path "M238.9,192c10.9-18.8,17.1-40.7,17.1-64s-6.2-45.2-17.1-64 L128,128L238.9,192z"
            :hint "Rectangle Tool (M)"
            :icon :stroke-rectangle}
   :line   {:type "line"
            :path "M238.9,192L128,128v128C175.4,256,216.7,230.3,238.9,192z"
            :hint "Line Tool (\\)"
            :icon :stroke-line}
   :pen    {:type "pencil"
            :path "M17.1,192c22.1,38.3,63.5,64,110.9,64V128L17.1,192z"
            :hint "Pencil Tool (N)"
            :icon :stroke-pencil}
   :text   {:type "text"
            :path "M17.1,64C6.2,82.8,0,104.7,0,128s6.2,45.2,17.1,64L128,128 L17.1,64z"
            :hint "Text Tool (T)"
            :icon :stroke-text}
   :select {:type "select"
            :path "M128,0C80.6,0,39.3,25.7,17.1,64L128,128V0z"
            :hint "Select Tool (V)"
            :icon :stroke-cursor}})

(defn menu-toggle [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            main-menu-learned? (get-in app state/main-menu-learned-path)]
        (html
          [:a.menu-toggle {:on-click (if (overlay-visible? app)
                                       #(cast! :overlay-menu-closed)
                                       #(cast! :main-menu-opened))
                           :role "button"
                           :class (when (overlay-visible? app)
                                    (concat
                                      ["bkg-light"]
                                      (if (< 1 (overlay-count app))
                                        ["back"]
                                        ["close"])))
                           :data-right (when-not main-menu-learned?
                                         (if (overlay-visible? app) "Close Menu" "Open Menu"))
                           :title (when main-menu-learned?
                                    (if (overlay-visible? app) "Close Menu" "Open Menu"))}
           (common/icon :menu)])))))

(defn info-toggle [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            info-button-learned? (get-in app state/info-button-learned-path)]
        (html
          [:a.info-toggle {:on-click #(cast! :overlay-info-toggled)
                           :role "button"
                           :class (concat
                                    ["hud-excess"]
                                    (when-not info-button-learned? ["hover"]))
                           :data-right (when-not info-button-learned? "What is Precursor?")
                           :title (when info-button-learned? "What is Precursor?")}
           (common/icon :info)])))))

(defn landing-toggle [app owner]
  (reify
    om/IRender
    (render [_]
      (let [cast! (om/get-shared owner :cast!)
            info-button-learned? (get-in app state/info-button-learned-path)]
        (html
          [:a.landing-toggle {:class "hud-excess"
                              :on-click #(cast! :landing-opened)
                              :role "button"}
           (common/icon :info)])))))

(defn chat-toggle [app owner]
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
          [:a.chat-toggle {:class (concat
                                    ["hud-excess"]
                                    (when-not chat-opened? ["open"]))
                           :on-click #(cast! :chat-toggled)
                           :role "button"
                           :data-left (when-not chat-button-learned?
                                        (if chat-opened? "Close Chat" "Open Chat"))
                           :title (when chat-button-learned?
                                    (if chat-opened? "Close Chat" "Open Chat"))}
           (common/icon :chat)
           (when (and (not chat-opened?) (pos? unread-chat-count))
             [:i.unseen-eids (str unread-chat-count)])])))))

(defn mouse-stats [app owner]
  (reify
    om/IRender
    (render [_]
      (html
        [:div.mouse-stats {:class "hud-excess"}
         (if (:mouse app)
           (pr-str (select-keys (:mouse app) [:x :y :rx :ry]))
           "{:x 0, :y 0, :rx 0, :ry 0}")]))))

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

(defn viewers [app owner]
  (reify
    om/IRender
    (render [_]
      (html
        (let [client-id (:client-id app)]

          ;; TODO deciding whether to edit name inline or not...
          ;; [:section.chat-people
          ;;  (let [show-mouse? (get-in app [:subscribers client-id :show-mouse?])]
          ;;    [:a.people-you {:key client-id
          ;;                    :data-bottom (when-not (get-in app [:cust :name]) "Click to edit")
          ;;                    :role "button"
          ;;                    :on-click #(if can-edit?
          ;;                                 (om/set-state! owner :editing-name? true)
          ;;                                 (cast! :overlay-username-toggled))}
          ;;     (common/icon :user (when show-mouse? {:path-props
          ;;                                           {:style
          ;;                                            {:stroke (get-in app [:subscribers client-id :color])}}}))

          ;;     (if editing-name?
          ;;       [:form {:on-submit #(do (when-not (str/blank? new-name)
          ;;                                 (cast! :self-updated {:name new-name}))
          ;;                               (om/set-state! owner :editing-name? false)
          ;;                               (om/set-state! owner :new-name "")
          ;;                               (utils/stop-event %))
          ;;               :on-blur #(do (when-not (str/blank? new-name)
          ;;                               (cast! :self-updated {:name new-name}))
          ;;                             (om/set-state! owner :editing-name? false)
          ;;                             (om/set-state! owner :new-name "")
          ;;                             (utils/stop-event %))
          ;;               :on-key-down #(when (= "Escape" (.-key %))
          ;;                               (om/set-state! owner :editing-name? false)
          ;;                               (om/set-state! owner :new-name "")
          ;;                               (utils/stop-event %))}
          ;;        [:input {:type "text"
          ;;                 :ref "name-edit"
          ;;                 :tab-index 1
          ;;                 ;; TODO: figure out why we need value here
          ;;                 :value new-name
          ;;                 :on-change #(om/set-state! owner :new-name (.. % -target -value))}]]
          ;;       [:span (or (get-in app [:cust :name]) "You")])])
          ;;  (for [[id {:keys [show-mouse? color cust-name]}] (dissoc (:subscribers app) client-id)
          ;;        :let [id-str (or cust-name (apply str (take 6 id)))]]
          ;;    [:a {:title "Ping this person in chat."
          ;;         :role "button"
          ;;         :key id
          ;;         :on-click #(cast! :chat-user-clicked {:id-str id-str})}
          ;;     (common/icon :user (when show-mouse? {:path-props {:style {:stroke color}}}))
          ;;     [:span id-str]])]

          [:div.viewers {:class (concat
                                  ["hud-excess"]
                                  (when (< 4 (count (:subscribers app))) ["overflowing"]))}
           (let [show-mouse? (get-in app [:subscribers client-id :show-mouse?])]
             [:div.viewer.viewer-self
              (common/icon :user (when show-mouse? {:path-props
                                                    {:style
                                                     {:stroke (get-in app [:subscribers client-id :color])}}}))
              [:div.viewer-name {:title "X and X others are viewing this doc."
                                 :data-name (or (get-in app [:cust :name]) "You")
                                 :data-count (count (:subscribers app))}]])
           (for [[id {:keys [show-mouse? color cust-name]}] (dissoc (:subscribers app) client-id)
                 :let [id-str (or cust-name (apply str (take 6 id)))]]
             [:div.viewer {:key id}
              (common/icon :user (when show-mouse? {:path-props {:style {:stroke color}}}))
              [:div.viewer-name id-str]])])))))

(defn hud [app owner]
  (reify
    om/IRender
    (render [_]
      (html
        (let [right-click-learned? (get-in app state/right-click-learned-path)]
         [:div.app-hud
          (om/build viewers app)
          (om/build menu-toggle app)
          (om/build chat-toggle app)
          (om/build landing-toggle app)
          (om/build mouse-stats app)
          ;; deciding whether to get rid of this
          ;; (when-not (:cust app)
          ;;   (om/build info-toggle app))
          (when (and (not right-click-learned?) (:mouse app))
            (om/build radial-hint app))
          (when (get-in app [:menu :open?])
            (om/build radial-menu app))
          ])))))

