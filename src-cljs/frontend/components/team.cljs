(ns frontend.components.team
  (:require [datascript :as d]
            [frontend.auth :as auth]
            [frontend.components.common :as common]
            [frontend.components.permissions :as permissions]
            [frontend.datascript :as ds]
            [frontend.db :as fdb]
            [frontend.models.doc :as doc-model]
            [frontend.sente :as sente]
            [frontend.urls :as urls]
            [frontend.utils :as utils]
            [om.core :as om]
            [taoensso.sente])
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
         [:section.menu-view
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

(defn request-access [app owner]
  (reify
    om/IDisplayName (display-name [_] "Request Team Access")
    om/IInitState
    (init-state [_]
      {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      ;; TODO: a lot of this could be abstracted
      (fdb/add-attribute-listener (om/get-shared owner :team-db)
                                  :access-request/team
                                  (om/get-state owner :listener-key)
                                  (fn [tx-report]
                                    (om/refresh! owner))))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-attribute-listener (om/get-shared owner :team-db)
                                     :access-request/team
                                     (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! team-db]} (om/get-shared owner)
            team (:team app)
            access-requests (ds/touch-all '[:find ?t
                                            :in $ ?team-uuid
                                            :where [?t :access-request/team ?team-uuid]]
                                          @team-db (:team/uuid team))]
        (html
         [:section.menu-view
          [:div.content
           [:h2.make
            "Join your team"]
           (if (empty? access-requests)
             (list
              [:p.make
               "Request access below and we'll notify the team owner."]
              [:div.menu-buttons.make
               [:a.menu-button {:on-click #(cast! :team-permission-requested {:team-uuid (:team/uuid (:team app))})
                                :role "button"}
                "Request Access"]])
             [:p.make
              [:span
               "Okay, we notified the owner of the team about your request. "
               "While you wait for a response, try prototyping in "]
              [:a {:href (urls/absolute-url "/new" :subdomain nil)
                   :target "_self"}
               "your own document"]
              [:span "."]])]])))))

(defn your-teams [app owner]
  (reify
    om/IDisplayName (display-name [_] "Your Teams Overlay")
    om/IDidMount
    (did-mount [_]
      (sente/send-msg (om/get-shared owner :sente) [:cust/fetch-teams]
                      20000
                      (fn [res]
                        (if (taoensso.sente/cb-success? res)
                          (om/set-state! owner :teams (:teams res))
                          (comment "do something about errors")))))
    om/IRenderState
    (render-state [_ {:keys [teams]}]
      (html
        [:section.menu-view

         [:a.vein.make.menu-new-team {:href (if (empty? teams) "/pricing" "/trial")}
          (common/icon :plus)
          "Start a new team."]

         (if (nil? teams)

           [:div.content.make
            [:div.loading "Loading..."]]

           (if (empty? teams)
             [:p.content.make "You don't have any teams, yet"]
             (for [team (sort-by :team/subdomain teams)]
               [:a.vein.make {:key (:team/subdomain team)
                              :href (urls/absolute-doc-url (:team/intro-doc team)
                                                           :subdomain(:team/subdomain team))}
                (common/icon :team)
                (:team/subdomain team)])))]))))

(defn team-start [app owner]
  (reify
    om/IDisplayName (display-name [_] "Overlay Team Start")
    om/IRender
    (render [_]
      (let [{:keys [cast! db]} (om/get-shared owner)
            doc-id (:document/id app)
            doc (doc-model/find-by-id @db doc-id)]
        (html
         [:section.menu-view
          [:div.veins
           (if (auth/has-team-permission? app (:team/uuid (:team app)))
             (list
              [:a.vein.make {:href (urls/overlay-path doc-id "team-settings")
                             :role "button"}
               (common/icon :users)
               [:span "Add Teammates"]]
              [:a.vein.make {:href (urls/overlay-path doc-id "team-doc-viewer")
                             :role "button"}
               (common/icon :docs-team)
               [:span "Team Documents"]]
              [:a.vein.make {:href (urls/overlay-path doc-id "plan")
                             :role "button"}
               (common/icon :credit)
               [:span "Billing"]])
             [:a.vein.make {:href (urls/overlay-path doc-id "request-team-access")
                            :role "button"}
              (common/icon :sharing)
              [:span "Request Access"]])
           [:a.vein.make {:href (urls/overlay-path doc-id "your-teams")
                          :role "button"}
            (common/icon :team)
            [:span "Your Teams"]]]])))))
