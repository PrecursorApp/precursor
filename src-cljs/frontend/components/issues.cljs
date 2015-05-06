(ns frontend.components.issues
  (:require [cljs-time.core :as time]
            [cljs-time.format :as time-format]
            [clojure.string :as str]
            [datascript :as d]
            [frontend.components.common :as common]
            [frontend.datascript :as ds]
            [frontend.datetime :as datetime]
            [frontend.db :as fdb]
            [frontend.models.issue :as issue-model]
            [frontend.sente :as sente]
            [frontend.urls :as urls]
            [frontend.utils :as utils]
            [goog.string :as gstring]
            [goog.string.format]
            [om.core :as om]
            [om.dom :as dom])
  (:require-macros [sablono.core :refer (html)])
  (:import [goog.ui IdGenerator]))

(defn issue-form [_ owner]
  (reify
    om/IInitState (init-state [_] {:issue-title ""})
    om/IRenderState
    (render-state [_ {:keys [issue-title]}]
      (let [issue-db (om/get-shared owner :issue-db)
            cust (om/get-shared owner :cust)]
        (html
          [:form.menu-invite-form.make {:on-submit #(do (utils/stop-event %)
                                                      (when (seq issue-title)
                                                        (d/transact! issue-db [{:db/id -1
                                                                                :issue/created-at (datetime/server-date)
                                                                                :issue/title issue-title
                                                                                :issue/author (:cust/email cust)
                                                                                :frontend/issue-id (utils/squuid)}])
                                                        (om/set-state! owner :issue-title "")))}
           [:input {:type "text"
                    :value issue-title
                    :required "true"
                    :data-adaptive ""
                    :onChange #(om/set-state! owner :issue-title (.. % -target -value))}]
           [:label {:data-placeholder (gstring/format "Sounds good; %s characters so far"
                                                      (count issue-title))
                    :data-placeholder-nil "How can we improve Precursor?"
                    :data-placeholder-busy "Your idea in 64 characters or less"}]])))))

(defn comment-form [{:keys [issue-id parent-id]} owner {:keys [issue-db]}]
  (reify
    om/IInitState (init-state [_] {:comment-body ""})
    om/IRenderState
    (render-state [_ {:keys [comment-body]}]
      (let [issue-db (om/get-shared owner :issue-db)
            cust (om/get-shared owner :cust)]
        (html
         [:form.comment-input {:on-submit #(do (utils/stop-event %)
                                 (when (seq comment-body)
                                   (d/transact! issue-db [{:db/id issue-id
                                                           :issue/comments (merge {:db/id -1
                                                                                   :comment/created-at (datetime/server-date)
                                                                                   :comment/body comment-body
                                                                                   :comment/author (:cust/email cust)
                                                                                   :frontend/issue-id (utils/squuid)}
                                                                                  (when parent-id
                                                                                    {:comment/parent parent-id}))}])
                                   (om/set-state! owner :comment-body "")))}

          [:div.adaptive
          [:textarea.adaptive-textarea {:data-adaptive ""
                      :required true
                   :value comment-body
                   :onChange #(om/set-state! owner :comment-body (.. % -target -value))}]
          [:label.adaptive-label {;:data-placeholder "What do you think?"
                                  ; :data-typing "What do you want to say?"
                                  :data-default "What do you think?"
                                  :data-forgot "To be continued"}]
          [:input.adaptive-submit.menu-button {:type "submit"
                                               :value "Comment"}]]

          ])))))

;; XXX: handle logged-out users
(defn vote-box [{:keys [issue]} owner]
  (reify
    om/IRender
    (render [_]
      (let [{:keys [cast! issue-db cust]} (om/get-shared owner)
            voted? (d/q '[:find ?e .
                          :in $ ?issue-id ?email
                          :where
                          [?issue-id :issue/votes ?e]
                          [?e :vote/cust ?email]]
                        @issue-db (:db/id issue) (:cust/email cust))]
        (html
          [:div.issue-vote {:role "button"
                            :class (str "issue-vote-" (if voted? "true" "false"))
                            :title (when voted? "Undo vote.")
                            :on-click #(d/transact! issue-db
                                                    [{:db/id (:db/id issue)
                                                      :issue/votes {:db/id -1
                                                                    :frontend/issue-id (utils/squuid)
                                                                    :vote/cust (:cust/email cust)}}])}
           [:div.issue-polls.issue-count (count (:issue/votes issue))]
           [:div.issue-polls.issue-arrow (common/icon :north)]
           [:div.issue-polls.issue-guide "vote"]])))))


(defn single-comment [{:keys [comment-id issue-id]} owner {:keys [ancestors]
                                                           :or {ancestors #{}}}]
  (reify
    om/IInitState
    (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))
                     :replying? false})
    om/IDidMount
    (did-mount [_]
      (fdb/add-entity-listener (om/get-shared owner :issue-db)
                               comment-id
                               (om/get-state owner :listener-key)
                               (fn [tx-report]
                                 (om/refresh! owner)))
      (fdb/add-attribute-listener (om/get-shared owner :issue-db)
                                  :comment/parent
                                  (om/get-state owner :listener-key)
                                  (fn [tx-report]
                                    (when (first (filter #(= comment-id (:v %))
                                                         (:tx-data tx-report)))
                                      (om/refresh! owner)))))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-entity-listener (om/get-shared owner :issue-db)
                                  comment-id
                                  (om/get-state owner :listener-key))
      (fdb/remove-attribute-listener (om/get-shared owner :issue-db)
                                     :comment/parent
                                     (om/get-state owner :listener-key)))
    om/IRenderState
    (render-state [_ {:keys [replying?]}]
      (let [{:keys [issue-db cast!]} (om/get-shared owner)
            comment (d/entity @issue-db comment-id)]
        (html
         [:div.issue-comment
          [:div.comment-body
           [:div.comment-author
            [:span.comment-avatar (common/icon :user)]
            [:span.comment-name (str " " (:comment/author comment))]
            [:span.comment-datetime (str " " (datetime/month-day (:comment/created-at comment)))]]
           [:div.comment-content (:comment/body comment)]]
          [:div.comment-foot
           ; [:span (common/icon :user)]
           ; [:span " author "]
           (if replying?
             (om/build comment-form {:issue-id issue-id
                                     :parent-id comment-id})
             (list
               ; [:a {:role "button"} "Upvote"]
               ; [:span " or "]
               [:a {:role "button"
                    :on-click #(om/set-state! owner :replying? true)}
                "reply"]
               ; [:span ", "]
               ; [:span (str " " (datetime/month-day (:comment/created-at comment)))]

             ))
           ; [:span (str " " (datetime/month-day (:comment/created-at comment)))]

           ]
          (when-not (contains? ancestors (:db/id comment)) ; don't render cycles
            [:div.comment-children
             (for [id (issue-model/direct-descendants @issue-db comment)]
               (om/build single-comment {:issue-id issue-id
                                         :comment-id id}
                         {:key :comment-id
                          :opts {:ancestors (conj ancestors (:db/id comment))}}))])])))))

(defn comments [{:keys [issue]} owner]
  (reify
    om/IRender
    (render [_]
      (let [{:keys [issue-db]} (om/get-shared owner)
            comments (issue-model/top-level-comments issue)]
        (html
         [:div.issue-comments
          [:h4 (str (count comments) " comment" (when (< 1 (count comments)) "s"))]
          (for [{:keys [db/id]} comments]
            (om/build single-comment {:comment-id id
                                      :issue-id (:db/id issue)}
                      {:key :comment-id}))])))))

(defn issue [{:keys [issue-id]} owner]
  (reify
    om/IInitState
    (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))
                     :title nil
                     :description nil})
    om/IDidMount
    (did-mount [_]
      (fdb/add-entity-listener (om/get-shared owner :issue-db)
                               issue-id
                               (om/get-state owner :listener-key)
                               (fn [tx-report]
                                 ;; This should ask the user if he wants to reload
                                 (om/refresh! owner))))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-entity-listener (om/get-shared owner :issue-db)
                                  issue-id
                                  (om/get-state owner :listener-key)))
    om/IRenderState
    (render-state [_ {:keys [title description]}]
      (let [{:keys [cast! issue-db]} (om/get-shared owner)
            issue (utils/inspect (ds/touch+ (d/entity @issue-db issue-id)))]
        (html
         [:div
          [:div.single-issue-head
           ; (om/build vote-box {:issue issue})
           ; [:h4 (or title (:issue/title issue ""))]
           (om/build vote-box {:issue issue})
           [:div.single-issue-info
           [:h4 (or title (:issue/title issue ""))]

           [:div.issue-tags
            [:div.issue-tag.issue-type "feature"]
            [:div.issue-tag.issue-status "started"]
            [:div.issue-tag.issue-author (:issue/author issue)]
            ]

           ; [:div.comment-author
           ;  [:span.comment-avatar (common/icon :user)]
           ;  [:span.comment-name (str " " (:issue/author issue))]
           ;  [:span.comment-datetime (str " " (datetime/month-day (:issue/created-at issue)))]]

           ]]

          ; [:p "by: " (:issue/author issue)]

          ; [:p "Title "
          ;  [:input {:value (or title (:issue/title issue ""))
          ;           :on-change #(om/set-state! owner :title (.. % -target -value))}]]

          ; [:p "Description "
          ;  [:input {:value (or description (:issue/description issue ""))
          ;           :on-change #(om/set-state! owner :description (.. % -target -value))}]]

          ; [:input {:value (or description (:issue/description issue ""))
          ;          :on-change #(om/set-state! owner :description (.. % -target -value))}]

          ; [:div.comment-author
          ;  [:span.comment-avatar (common/icon :user)]
          ;  [:span.comment-name (str " " (:issue/author issue))]
          ;  [:span.comment-datetime (str " " (datetime/month-day (:issue/created-at issue)))]]

          ; [:div.comment-content
          ;  (or description (:issue/description issue ""))]

          [:div.single-issue-body

           ; [:div.comment-author
           ;  [:span.comment-avatar (common/icon :user)]
           ;  [:span.comment-name (str " " (:issue/author issue))]
           ;  [:span.comment-datetime (str " " (datetime/month-day (:issue/created-at issue)))]]

          (if description

            [:div.comment-content
             (or description (:issue/description issue ""))]

            [:div.comment-content "There's no description yet."])]

          [:div.single-issue-foot
            [:a {:role "button"} "reply"]
           ]

          ; [:div.issue-tags
          ;  ; [:div.issue-tag.issue-type "feature"]
          ;  ; [:div.issue-tag.issue-status "started"]
          ;  [:div.issue-tag.issue-author (:issue/author issue)]]

          ; [:p
          ;  [:a {:role "button"
          ;       :on-click #(d/transact! issue-db [(utils/remove-map-nils
          ;                                          {:db/id issue-id
          ;                                           :issue/title title
          ;                                           :issue/description description})])}
          ;   "Save"]
          ;  " "
          ;  [:a {:on-click #(d/transact! issue-db [[:db.fn/retractEntity issue-id]])
          ;       :role "button"}
          ;   "Delete"]]

          ; [:div.issue-comment-input.adaptive-placeholder {:contentEditable true
          ;                                                 :data-before "What do you think about this issue?"
          ;                                                 :data-after "Hit enter to submit your comment"
          ;                                                 :data-forgot "You forgot to submit!"}]

          (om/build comment-form {:issue-id issue-id})

          ; [:div.calls-to-action
          ; [:a.menu-button {:role "button"} "Comment"]]

          ; [:h4 (str "0" " comments")]

          (om/build comments {:issue issue})

          ; [:p "Make a new comment:"]

          ; (om/build comment-form {:issue-id issue-id})

          ])))))

(defn issue-summary [{:keys [issue-id]} owner]
  (reify
    om/IInitState
    (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (fdb/add-entity-listener (om/get-shared owner :issue-db)
                               issue-id
                               (om/get-state owner :listener-key)
                               (fn [tx-report]
                                 (om/refresh! owner))))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-entity-listener (om/get-shared owner :issue-db)
                                  issue-id
                                  (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! issue-db]} (om/get-shared owner)
            issue (ds/touch+ (d/entity @issue-db issue-id))]
        (html
         [:div.issue
          (om/build vote-box {:issue issue})
          [:div.issue-info
           [:a.issue-title {:on-click #(cast! :issue-expanded {:issue-id issue-id})
                :role "button"}
            (:issue/title issue)]
           [:div.issue-tags
            ; [:div.issue-tag.issue-type "feature"]
            ; [:div.issue-tag.issue-status "started"]
            [:div.issue-tag.issue-author (:issue/author issue)]]]])))))

(defn issues [app owner {:keys [submenu]}]
  (reify
    om/IInitState
    (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (fdb/setup-issue-listener!
       (om/get-shared owner :issue-db)
       (om/get-state owner :listener-key)
       (om/get-shared owner :comms)
       (om/get-shared owner :sente))
      (fdb/add-attribute-listener (om/get-shared owner :issue-db)
                                  :frontend/issue-id
                                  (om/get-state owner :listener-key)
                                  (fn [tx-report]
                                    (om/refresh! owner)))
      (sente/subscribe-to-issues (:sente app)
                                 (om/get-shared owner :comms)
                                 (om/get-shared owner :issue-db)))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :issue-db) (om/get-state owner :listener-key))
      (fdb/remove-attribute-listener (om/get-shared owner :issue-db)
                                     :frontend/issue-id
                                     (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [{:keys [cast! issue-db cust]} (om/get-shared owner)]
        (html
         [:div.menu-view
          [:div.content
           (when-not submenu
             [:div.make {:key "issue-form"}
              (om/build issue-form {})])
           [:div.make {:key (or submenu "summary")}
            (if submenu
              (om/build issue {:issue-id (:active-issue-id app)} {:key :issue-id})
              (when-let [issues (seq (map #(d/entity @issue-db (:e %)) (d/datoms @issue-db :aevt :issue/title)))]
                (om/build-all issue-summary (map (fn [i] {:issue-id (:db/id i)}) (sort (issue-model/issue-comparator cust (datetime/server-date)) issues))
                              {:key :issue-id
                               :opts {:issue-db issue-db}})))]]])))))
