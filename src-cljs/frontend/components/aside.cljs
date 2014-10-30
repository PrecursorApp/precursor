(ns frontend.components.aside
  (:require [frontend.async :refer [put!]]
            [frontend.components.common :as common]
            [frontend.state :as state]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]]))

(defn menu [app owner]
  (reify
    om/IRender
    (render [_]
      (let [controls-ch (om/get-shared owner [:comms :controls])
            show-grid? (get-in app state/show-grid-path)
            night-mode? (get-in app state/night-mode-path)
            client-id (:client-uuid app)]
       (html
         [:div.aside-menu
          [:a {:href "/" :target "_self"}
           (common/icon :logomark-precursor)
           [:span "Precursor"]]
          ;; hide extra buttons until there's features to back them up
          ;; [:button
          ;;  (common/icon :user)
          ;;  [:span "Login"]]
          ;; [:button
          ;;  (common/icon :download)
          ;;  [:span "Download"]]
          ;; [:button.collaborators
          ;;  (common/icon :users)
          ;;  [:span "Collaborators"]]
          (let [show-mouse? (get-in app [:subscribers client-id :show-mouse?])]
            [:button {:title "You're viewing this document. Try inviting others. Click to toggle sharing your mouse position."
                      :on-click #(put! controls-ch [:show-mouse-toggled {:client-uuid client-id :show-mouse? (not show-mouse?)}])}
             [:object
              (common/icon :user (when show-mouse? {:path-props
                                                    {:style
                                                     {:stroke (apply str "#" (take 6 client-id))}}}))
              [:span "You"]]])
          (for [[id {:keys [show-mouse?]}] (dissoc (:subscribers app) client-id)
                :let [id-str (apply str (take 6 id))]]
            [:button {:title "An anonymous user is viewing this document. Click to toggle showing their mouse position."
                      :on-click #(put! controls-ch [:show-mouse-toggled {:client-uuid id :show-mouse? (not show-mouse?)}])}
             (common/icon :user (when show-mouse? {:path-props {:style {:stroke (str "#" id-str)}}}))
             [:span id-str]])
          [:div.aside-settings
           [:button {:disabled "true"}
            (common/icon :settings)
            [:span "Settings"]]
          [:div.settings-menu
           [:button {:on-click #(put! controls-ch [:show-grid-toggled])}
            [:span "Show Grid"]
            (if show-grid?
              (common/icon :check)
              (common/icon :times))]
           [:button {:on-click #(put! controls-ch [:night-mode-toggled])}
            [:span "Night Mode"]
            (if night-mode?
              (common/icon :check)
              (common/icon :times))]]]])))))
