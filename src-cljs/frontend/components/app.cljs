(ns frontend.components.app
  (:require [cemerick.url :as url]
            [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.set :as set]
            [clojure.string :as str]
            [datascript :as d]
            [frontend.async :refer [put!]]
            [frontend.auth :as auth]
            [frontend.components.aside :as aside]
            [frontend.components.inspector :as inspector]
            [frontend.components.key-queue :as keyq]
            [frontend.components.canvas :as canvas]
            [frontend.components.common :as common]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.seq :refer [dissoc-in select-in]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [ankha.core :as ankha])
  (:require-macros [frontend.utils :refer [html]])
  (:import [goog.ui IdGenerator]))

(def tools-templates
  {:circle {:type :tool-circle
            :path "M302.9,128C292,109.1,276.2,92.8,256,81.1S213.8,64,192,64l0,128 L302.9,128z"}
   :rect   {:type :tool-rect
            :path "M302.9,256c10.9-18.8,17.1-40.7,17.1-64s-6.2-45.2-17.1-64L192,192 L302.9,256z"}
   :line   {:type :tool-line
            :path "M192,320c21.8,0,43.8-5.5,64-17.2s36-28,46.9-46.8L192,192L192,320z"}
   :pen    {:type :tool-pen
            :path "M81.1,256c10.9,18.8,26.7,35.2,46.9,46.8s42.2,17.2,64,17.2l0-128 L81.1,256z"}
   :text   {:type :tool-text
            :path "M81.1,128C70.2,146.8,64,168.7,64,192s6.2,45.2,17.1,64L192,192 L81.1,128z"}
   :select {:type :tool-select
            :path "M192,64c-21.8,0-43.8,5.5-64,17.2s-36,28-46.9,46.8L192,192L192,64z"}})

(def keymap
  (atom nil))

(defn app* [app owner]
  (reify
    om/IDisplayName (display-name [_] "App")
    om/IRender
    (render [_]
      (if-not (:navigation-point app)
        (html [:div#app])

        (let [controls-ch (om/get-shared owner [:comms :controls])
              persist-state! #(put! controls-ch [:state-persisted])
              restore-state! #(put! controls-ch [:state-restored])
              logged-in? (get-in app state/user-path)]
          (reset! keymap {["ctrl+s"] persist-state!
                          ["ctrl+r"] restore-state!})
          (html
           [:div#app
            (om/build keyq/keyboard-handler app
                      {:opts {:keymap keymap
                              :error-ch (get-in app [:comms :errors])}})]))))))

(defn auth-link [data owner]
  (reify
    om/IRender
    (render [_]
      (html
       (if (:cust data)
         [:form {:method "post" :action "/logout" :ref "logout-form"}
          [:input {:type "hidden" :name "__anti-forgery-token" :value (utils/csrf-token)}]
          [:input {:type "hidden" :name "redirect-to" :value (-> (.-location js/window)
                                                                 (.-href)
                                                                 (url/url)
                                                                 :path)}]
          [:a.action-logout {:on-click #(.submit (om/get-node owner "logout-form"))
                             :data-right "Logout"}
           (common/icon :logout)]]
         [:a.action-login {:href (auth/auth-url)
                           :data-right "Sign Up"}
          (common/icon :login)])))))

(defn main-actions [data owner]
  (reify
    om/IInitState
    (init-state [_] {:unseen-chats-count 0
                     :listener-key (.getNextUniqueId (.getInstance IdGenerator))})
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
            aside-opened? (get-in data state/aside-menu-opened-path)
            db @db
            chat-timestamps (->> (d/q '[:find ?t
                                        :where [?t :chat/body]]
                                      db)
                                 (map #(:server/timestamp (d/entity db (first %)))))
            last-read-time (get-in data (state/last-read-chat-time-path (:document/id data)))
            unread-chat-count (if last-read-time
                                (count (filter #(> % last-read-time) chat-timestamps))
                                ;; add one for the dummy message
                                (inc (count chat-timestamps)))
            info-button-learned? (get-in data state/info-button-learned-path)]
        (html
         [:div.main-actions
          [:a.action-menu {:on-click #(cast! :aside-menu-toggled)
                           :class (when-not aside-opened? "closed")
                           :data-right (if aside-opened? "Close Menu" "Open Menu")}
           (common/icon :menu)]
          (when (and (not aside-opened?) (pos? unread-chat-count))
            [:div.unseen-eids (str unread-chat-count)])
          (om/build auth-link data)
          [:a.action-newdoc {:href "/"
                             :target "_self"
                             :data-right "New Document"}
           (common/icon :newdoc)]
          [:a.action-info {:on-click #(cast! :overlay-info-toggled)
                           :class (when-not info-button-learned? "hover")
                           :data-right "What is this thing?"}
           (common/icon :info)]])))))


(defn app [app owner]
  (reify
    om/IRender
    (render [_]
      (let [{:keys [cast! handlers]} (om/get-shared owner)
            aside-opened? (get-in app state/aside-menu-opened-path)
            overlay-info-open? (get-in app state/overlay-info-opened-path)
            right-click-learned? (get-in app state/right-click-learned-path)]
        (html [:div#app
               (om/build aside/menu app)
               [:main.app-main {:onContextMenu (fn [e]
                                                 (.preventDefault e)
                                                 (.stopPropagation e))}
                (om/build canvas/svg-canvas app)
                (om/build main-actions (select-in app [state/aside-menu-opened-path
                                                       state/info-button-learned-path
                                                       [:cust]
                                                       [:document/id]
                                                       (state/last-read-chat-time-path (:document/id app))]))
                (when (and (:mouse app) (not= :touch (:type (:mouse app))))
                  [:div.mouse-stats
                   (pr-str (select-keys (:mouse app) [:x :y :rx :ry]))])
                (when (get-in app [:menu :open?])
                  [:div.radial-menu {:style {:top  (- (get-in app [:menu :y]) 192)
                                             :left (- (get-in app [:menu :x]) 192)}}
                   [:svg {:width "384" :height "384"}
                    (for [[tool template] tools-templates]
                      [:path.radial-button {:d (:path template)
                                            :key tool
                                            :on-mouse-up #(do (cast! :tool-selected [tool]))
                                            :on-touch-end #(do (cast! :tool-selected [tool]))}])]
                   (for [[tool template] tools-templates]
                     [:div.radial-tool-type {:key tool}
                      (common/icon (:type template))
                      [:span (name tool)]])
                   [:div.radial-menu-nub]])
                (when (and (not right-click-learned?) (:mouse app))
                  [:div.radial-tip {:style {:top  (+ (get-in app [:mouse :y]) 16)
                                            :left (+ (get-in app [:mouse :x]) (if aside-opened? (- 16 256) 16) )}}
                   (if (= :touch (get-in app [:mouse :type]))
                     "Tap and hold to select tool"
                     "Right-click to select tool")])]
               [:div.app-overlay
                [:figure.overlay-info {:on-click #(cast! :overlay-info-toggled)
                                       :class (when-not overlay-info-open? "hidden")}
                 [:div.info-background]
                 [:a.info-close {:role "button"}
                  (common/icon :times)]
                 [:article {:on-click #(.stopPropagation :overlay-info-toggled)}
                  [:h1 "What's this?"]
                  [:p "Precursor is a collaborative idea tool.
                      Think of it as a notebook with infinite pages – use it to create sketches, rapid prototypes, notes, and everything in between.
                      Share your URL to collaborate and you'll instantly have multiple people working in the same document.
                      It's still a work in progress, so if you have feedback or a great idea for us sketch it up and ping "
                      [:a {:on-click #(cast! :chat-link-clicked)
                           :role "button"}
                       "@prcrsr"]
                      " in the chat."]
                  [:div.info-buttons
                   [:button.info-okay {:on-click #(cast! :overlay-info-toggled)}
                    "Okay, sounds good."]
                   [:a.info-twitter {:href "https://twitter.com/prcrsr_app"
                                     :title "Keep track of our changes on Twitter."
                                     :target "_blank"}
                    "What should we add next?"]]]]]])))))
