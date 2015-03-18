(ns frontend.components.permissions
  (:require [frontend.components.common :as common]
            [frontend.utils :as utils]
            [frontend.utils.date :refer (date->bucket)])
  (:require-macros [sablono.core :refer (html)]))

(defn format-access-date [date]
  (date->bucket date :sentence? true))

;; TODO: add types to db
(defn access-entity-type [access-entity]
  (cond (or (contains? access-entity :permission/document)
            (contains? access-entity :permission/team))
        :permission

        (or (contains? access-entity :access-grant/document)
            (contains? access-entity :access-grant/team))
        :access-grant

        (or (contains? access-entity :access-request/document)
            (contains? access-entity :access-request/team))
        :access-request

        :else nil))

;; TODO: this should call om/build somehow
(defmulti render-access-entity (fn [entity cast!]
                                 (access-entity-type entity)))

(defmethod render-access-entity :permission
  [entity cast!]
  (html
   [:div.access-card.make {:key (:db/id entity)}
    [:div.access-avatar
     [:img.access-avatar-img
      {:src (utils/gravatar-url (:permission/cust entity))}]]
    [:div.access-details
     [:span
      {:title (:permission/cust entity)} (:permission/cust entity)]
     [:span.access-status
      (str "Was granted access " (format-access-date (:permission/grant-date entity)))]]]))

(defmethod render-access-entity :access-grant
  [entity cast!]
  (html
   [:div.access-card.make {:key (:db/id entity)}
    [:div.access-avatar
     [:img.access-avatar-img
      {:src (utils/gravatar-url (:access-grant/email entity))}]]
    [:div.access-details
     [:span
      {:title (:access-grant/email entity)} (:access-grant/email entity)]
     [:span.access-status
      (str "Was granted access " (format-access-date (:access-grant/grant-date entity)))]]]))

(defmethod render-access-entity :access-request
  [entity cast!]
  (html
   [:div.access-card.make {:key (:db/id entity)
                           :class (if (= :access-request.status/denied (:access-request/status entity))
                                    "denied"
                                    "requesting")}
    [:div.access-avatar
     [:img.access-avatar-img {:src (utils/gravatar-url (:access-request/cust entity))}]]
    [:div.access-details
     [:span {:title (:access-request/cust entity)} (:access-request/cust entity)]
     [:span.access-status
      (if (= :access-request.status/denied (:access-request/status entity))
        (str "Was denied access " (format-access-date (:access-request/deny-date entity)))
        (str "Requested access " (format-access-date (:access-request/create-date entity))))]]
    [:div.access-options
     (when-not (= :access-request.status/denied (:access-request/status entity))
       [:button.access-option
        {:role "button"
         :class "negative"
         :title "Decline"
         :on-click #(cast! :access-request-denied {:request-id (:db/id entity)
                                                   :doc-id (:access-request/document entity)
                                                   :team-uuid (:access-request/team entity)})}
        (common/icon :times)])
     [:button.access-option
      {:role "button"
       :class "positive"
       :title "Approve"
       :on-click #(cast! :access-request-granted {:request-id (:db/id entity)
                                                  :doc-id (:access-request/document entity)
                                                  :team-uuid (:access-request/team entity)})}
      (common/icon :check)]]]))
