(ns frontend.components.build-invites
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [frontend.async :refer [put!]]
            [frontend.components.common :as common]
            [frontend.components.forms :as forms]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.github :as gh-utils]
            [frontend.utils.vcs-url :as vcs-url]
            [om.core :as om :include-macros true])
  (:require-macros [frontend.utils :refer [html]]))

(defn invitees
  "Filters users to invite and returns only fields needed by invitation API"
  [users]
  (->> users
       (filter (fn [u] (and (:email u)
                            (:checked u))))
       (map (fn [u] (select-keys u [:email :login :id])))
       vec))

(defn invite-tile [user owner]
  (reify
    om/IRender
    (render [_]
      (let [{:keys [avatar_url email login index]} user
            controls-ch (om/get-shared owner [:comms :controls])]
        (html
         [:li
          [:div.invite-gravatar
           [:img {:src (gh-utils/make-avatar-url user)}]]
          [:div.invite-profile
           login
           [:input {:on-change #(utils/edit-input controls-ch (conj (state/build-github-user-path index) :email) %)
                    :required true
                    :type "email"
                    :value email
                    :id (str login "-email")}]
           [:label {:for (str login "-email")}
            [:i.fa.fa-exclamation-circle]
            " Fix Email"]]
          [:label.invite-select {:id (str login "-checkbox")}
           [:input {:type "checkbox"
                    :checked (boolean (:checked user))
                    :on-change #(utils/toggle-input
                                 controls-ch (conj (state/build-github-user-path index) :checked) %)}]]])))))

(defn build-invites [invite-data owner opts]
  (reify
    om/IWillMount
    (will-mount [_]
      (let [controls-ch (om/get-shared owner [:comms :controls])
            project-name (:project-name opts)]
        (put! controls-ch [:load-first-green-build-github-users {:project-name project-name}])))
    om/IRender
    (render [_]
      (let [controls-ch (om/get-shared owner [:comms :controls])
            project-name (:project-name opts)
            users (remove :following (:github-users invite-data))
            dismiss-form (:dismiss-invite-form invite-data)]
        (html
         [:div.first-green.invite-form {:class (when (or (empty? users) dismiss-form)
                                                 "animation-fadeout-collapse")}
          [:button {:on-click #(put! controls-ch [:dismiss-invite-form])}
           [:span "Dismiss "] [:i.fa.fa-times-circle]]
          [:header
           [:div.head-left
            (common/icon {:type :status :name :pass})]
           [:div.head-right
            [:h2 "Congratulations!"]
            [:p "You just got your first green build! Invite some of your collaborators below and never test alone!"]]]
          [:section
           [:a {:role "button"
                :on-click #(put! controls-ch [:invite-selected-all])}
            "all"]
           " / "
           [:a {:role "button"
                :on-click #(put! controls-ch [:invite-selected-none])}
            "none"]
           [:ul
            (om/build-all invite-tile users {:key :login})]]
          [:footer
           (forms/stateful-button
            [:button (let [users-to-invite (invitees users)]
                       {:data-success-text "Sent"
                        :on-click #(put! controls-ch [:invited-github-users {:invitees users-to-invite
                                                                             :project-name project-name}])})
             "Send Invites "
             [:i.fa.fa-envelope-o]])]])))))
