(ns frontend.components.team
  (:require [datascript :as d]
            [frontend.components.permissions :as permissions]
            [frontend.datascript :as ds]
            [frontend.utils :as utils]
            [om.core :as om])
  (:require-macros [sablono.core :refer (html)])
  (:import [goog.ui IdGenerator]))

(defn team-settings [app owner]
  (reify
    om/IDisplayName (display-name [_] "Team settings")
    om/IInitState
    (init-state [_]
      {:permission-grant-email ""
       :listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (d/listen! (om/get-shared owner :team-db)
                 (om/get-state owner :listener-key)
                 (fn [tx-report]
                   ;; TODO: better way to check if state changed
                   (when (seq (filter #(or (= :permission/team (:a %))
                                           (= :access-grant/team (:a %))
                                           (= :access-request/team (:a %))
                                           (= :access-request/status (:a %)))
                                      (:tx-data tx-report)))
                     (om/refresh! owner)))))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :team-db) (om/get-state owner :listener-key)))

    om/IRenderState
    (render-state [_ {:keys [permission-grant-email]}]
      (let [team (:team app)
            db (om/get-shared owner :team-db)
            permissions (ds/touch-all '[:find ?t :in $ ?team-uuid :where [?t :permission/team ?team-uuid]] @db (:team/uuid team))
            access-grants (ds/touch-all '[:find ?t :in $ ?team-uuid :where [?t :access-grant/team ?team-uuid]] @db (:team/uuid team))
            access-requests (ds/touch-all '[:find ?t :in $ ?team-uuid :where [?t :access-request/team ?team-uuid]] @db (:team/uuid team))
            cast! (om/get-shared owner :cast!)
            submit-form (fn [e]
                          (cast! :team-permission-grant-submitted {:email permission-grant-email})
                          (om/set-state! owner :permission-grant-email "")
                          (utils/stop-event e))]
        (html
         [:div.menu-view
          [:div.content
           [:p.make
            "Any docs you create in the " (:team/subdomain team)
            " subdomain will be private to your team by default."
            " Add your teammate's email to add them to your team."]
           [:form.menu-invite-form.make
            {:on-submit submit-form
             :on-key-down #(when (= "Enter" (.-key %))
                             (submit-form %))}
            [:input
             {:type "text"
              :required "true"
              :data-adaptive ""
              :value (or permission-grant-email "")
              :on-change #(om/set-state! owner :permission-grant-email (.. % -target -value))}]
            [:label
             {:data-placeholder "Teammate's email"
              :data-placeholder-nil "What's your teammate's email?"
              :data-placeholder-forgot "Don't forget to submit!"}]]
           (for [access-entity (sort-by (comp - :db/id) (concat permissions access-grants access-requests))]
             (permissions/render-access-entity access-entity cast!))]])))))
