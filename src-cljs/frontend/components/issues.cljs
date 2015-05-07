(ns frontend.components.issues
  (:require [cljs-time.core :as time]
            [cljs-time.format :as time-format]
            [clojure.set :as set]
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
    om/IDisplayName (display-name [_] "Issue form")
    om/IInitState (init-state [_] {:issue-title ""})
    om/IRenderState
    (render-state [_ {:keys [issue-title]}]
      (let [{:keys [issue-db cust cast!]} (om/get-shared owner)]
        (html
          [:form.adaptive {:on-submit #(do (utils/stop-event %)
                                         (when (seq issue-title)
                                           (let [tx (d/transact! issue-db [{:db/id -1
                                                                            :issue/created-at (datetime/server-date)
                                                                            :issue/title issue-title
                                                                            :issue/author (:cust/email cust)
                                                                            :issue/document :none
                                                                            :frontend/issue-id (utils/squuid)}])]
                                             (cast! :issue-expanded {:issue-id (d/resolve-tempid (:db-after tx) (:tempids tx) -1)}))
                                           (om/set-state! owner :issue-title "")))}
           [:textarea {:value issue-title
                       :required "true"
                       :onChange #(om/set-state! owner :issue-title (.. % -target -value))}]
           [:label {:data-label (gstring/format "Sounds good so far—%s characters left" (count issue-title))
                    :data-placeholder "How can we improve Precursor?"}]
           [:input {:type "submit"
                    :value "Submit idea."}]])))))

(defn comment-form [{:keys [issue-id parent-id]} owner {:keys [issue-db]}]
  (reify
    om/IDisplayName (display-name [_] "Comment form")
    om/IInitState (init-state [_] {:comment-body ""})
    om/IRenderState
    (render-state [_ {:keys [comment-body]}]
      (let [issue-db (om/get-shared owner :issue-db)
            cust (om/get-shared owner :cust)]
        (html
          [:form.adaptive.make {:on-submit #(do (utils/stop-event %)
                                              (when (seq comment-body)
                                                (d/transact! issue-db [{:db/id issue-id
                                                                        :issue/comments (merge {:db/id -1
                                                                                                :comment/created-at (datetime/server-date)
                                                                                                :comment/body comment-body
                                                                                                :comment/author (:cust/email cust)
                                                                                                :frontend/issue-id (utils/squuid)}
                                                                                               (when parent-id
                                                                                                 {:comment/parent parent-id}))}])
                                                (om/set-state! owner :comment-body ""))
                                              (om/set-state! owner :replying? false)
                                              )}

           [:textarea {:required true
                       :value comment-body
                       :onChange #(om/set-state! owner :comment-body (.. % -target -value))}]
           [:label {:data-label "What do you think?"
                    :data-forgot "To be continued"}]
           [:input.make {:type "submit"
                         :value "Comment."}]])))))

;; XXX: handle logged-out users
(defn vote-box [{:keys [issue]} owner]
  (reify
    om/IDisplayName (display-name [_] "Vote box")
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
                            :on-click #(d/transact! issue-db
                                                    [{:db/id (:db/id issue)
                                                      :issue/votes {:db/id -1
                                                                    :frontend/issue-id (utils/squuid)
                                                                    :vote/cust (:cust/email cust)}}])}
           [:div.issue-polls.issue-votes {:key (count (:issue/votes issue))}
            (count (:issue/votes issue))]
           [:div.issue-polls.issue-upvote
            (common/icon :north)]])))))

; (defn comment-foot [{:keys [comment-id issue-id]} owner]
;   (reify
;     om/IRender
;     (render [_]
;       (let [{:keys [issue-db cast!]} (om/get-shared owner)
;             comment (d/entity @issue-db comment-id)]
;         (html
;           [:p.issue-foot
;            [:span (common/icon :user) " "]
;            [:a {:role "button"}
;             (:comment/author comment)]
;            [:span " on "]
;            [:a {:role "button"}
;             (datetime/month-day (:comment/created-at comment))]
;            [:span " — "]
;            [:a {:role "button"
;                 :on-click #(om/set-state! owner :replying? true)}
;             "Reply"]

;            ; (when (om/get-state owner :replying?) (om/build comment-form {:issue-id issue-id}))

;            ])))))


(defn single-comment [{:keys [comment-id issue-id]} owner {:keys [ancestors]
                                                           :or {ancestors #{}}}]
  (reify
    om/IDisplayName (display-name [_] "Single comment")
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
          [:div.issue-comment.make
           [:div.issue-divider]
           [:p (:comment/body comment)]

           ; (om/build comment-foot comment)
           ; (om/build comment-foot {:comment-id comment-id
           ;                         :issue-id issue-id})

           [:p.issue-foot
            [:span (common/icon :user) " "]
            [:a {:role "button"}
             (:comment/author comment)]
            [:span " on "]
            [:a {:role "button"}
             (datetime/month-day (:comment/created-at comment))]
            [:span " — "]
            [:a {:role "button"
                 :on-click #(do
                              (if (om/get-state owner :replying?)
                                (om/set-state! owner :replying? false)
                                (om/set-state! owner :replying? true)))}
             (if (om/get-state owner :replying?) "Cancel" "Reply")]

            ; (when (om/get-state owner :replying?) (om/build comment-form {:issue-id issue-id}))

            ]

           (when (om/get-state owner :replying?)
             (om/build comment-form {:issue-id issue-id
                                     :parent-id comment-id}))

           ; [:div.issue-divider]

           ; [:div.comment-foot
           ;  ; [:span (common/icon :user)]
           ;  ; [:span " author "]


           ;  ; [:span "by "]

           ;  [:a {:role "button"}
           ;   ; [:span (common/icon :user)]
           ;   [:span (:comment/author comment)]]

           ;  [:span " on "]

           ;  [:a {:role "button"}
           ;   (datetime/month-day (:comment/created-at comment))]

           ;  ; [:span " • "]
           ;  [:span " — "]

           ;  [:a {:role "button"
           ;       :on-click #(om/set-state! owner :replying? true)}
           ;   "Reply"]

           ;  ; [:span "?"]


           ;    ; (om/build comment-form {:issue-id issue-id
           ;    ;                         :parent-id comment-id})



           ;  ; [:span (str " " (datetime/month-day (:comment/created-at comment)))]
           ;  ; [:a.comment-datetime {:role "button"} (str " " (datetime/month-day (:comment/created-at comment)))]
           ;  ; [:span " • "]
           ;  ; [:a {:role "button"}
           ;  ;  [:span (common/icon :clock)]
           ;  ;  [:span (datetime/month-day (:comment/created-at comment))]]
           ;  ; [:a {:role "button"}
           ;  ;  [:span (common/icon :user)]
           ;  ;  [:span (:comment/author comment)]]

           ;  ]
           (when (and (not (contains? ancestors (:db/id comment)))
                      (< 0 (count (issue-model/direct-descendants @issue-db comment)))) ; don't render cycles
             [:div.issue-comments
              (for [id (issue-model/direct-descendants @issue-db comment)]
                (om/build single-comment {:issue-id issue-id
                                          :comment-id id}
                          {:key :comment-id
                           :opts {:ancestors (conj ancestors (:db/id comment))}}))])

           ; [:div.issue-divider]

           ])))))

(defn comments [{:keys [issue]} owner]
  (reify
    om/IDisplayName (display-name [_] "Comments")
    om/IRender
    (render [_]
      (let [{:keys [issue-db]} (om/get-shared owner)
            comments (issue-model/top-level-comments issue)]
        (html
         [:div.issue-comments
          ; [:p.comments-count (str (count comments) " comment" (when (< 1 (count comments)) "s"))]
          (for [{:keys [db/id]} comments]
            (list
            ; [:div.issue-divider]
            (om/build single-comment {:comment-id id
                                      :issue-id (:db/id issue)}
                      {:key :comment-id})))])))))

(defn issue-summary [{:keys [issue-id]} owner]
  (reify
    om/IDisplayName (display-name [_] "Issue summary")
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
            issue (ds/touch+ (d/entity @issue-db issue-id))
            comment-count (count (:issue/comments issue))]
        (html
         [:div.issue-summary.content.make
          [:div.issue-info
           [:a.issue-title {:on-click #(cast! :issue-expanded {:issue-id issue-id})
                            :role "button"}
            ; (or title (:issue/title issue ""))
            (:issue/title issue)
            ]
           ; (issue-foot issue)

           [:p.issue-foot
            [:a {:role "button"}
             (str comment-count " comment" (when (not= 1 comment-count) "s"))]
            [:span " for "]
            [:a {:role "button"}
             ; "feature"
             ; "integration"
             "bugfix"]
            [:span " in "]
            [:a {:role "button"}
             ; "review"
             ; "production"
             "development."]]]
          (om/build vote-box {:issue issue})])))))

; (defn issue-summary [{:keys [issue-id]} owner]
;   (reify
;     om/IInitState
;     (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
;     om/IDidMount
;     (did-mount [_]
;       (fdb/add-entity-listener (om/get-shared owner :issue-db)
;                                issue-id
;                                (om/get-state owner :listener-key)
;                                (fn [tx-report]
;                                  (om/refresh! owner))))
;     om/IWillUnmount
;     (will-unmount [_]
;       (fdb/remove-entity-listener (om/get-shared owner :issue-db)
;                                   issue-id
;                                   (om/get-state owner :listener-key)))
;     om/IRender
;     (render [_]
;       (let [{:keys [cast! issue-db]} (om/get-shared owner)
;             issue (ds/touch+ (d/entity @issue-db issue-id))
;             comment-count (count (:issue/comments issue))]
;         (html
;           [:div.issue-summary
;            [:div.issue-info
;             [:a.issue-title {:on-click #(cast! :issue-expanded {:issue-id issue-id})
;                              :role "button"}
;              ; (or title (:issue/title issue ""))
;              (:issue/comments issue)
;              ]
;             ; (issue-foot issue)

;             [:p.issue-foot
;              [:a {:role "button"}
;               (str comment-count " comment" (when (< 1 comment-count) "s"))]
;              [:span " for "]
;              [:a {:role "button"}
;               ; "feature"
;               ; "integration"
;               "bugfix"]
;              [:span " in "]
;              [:a {:role "button"}
;               ; "review"
;               ; "production"
;               "development."]]]
;            (om/build vote-box {:issue issue})])))))

(defn issue [{:keys [issue-id]} owner]
  (reify
    om/IDisplayName (display-name [_] "Issue")
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
            issue (ds/touch+ (d/entity @issue-db issue-id))]
        (html
          [:div.menu-view.issue

           #_(when-not (keyword-identical? :none (:issue/document issue :none))
               [:a {:href (urls/absolute-doc-url (:issue/document issue) :subdomain nil)
                    :target "_blank"}
                [:img {:src (urls/absolute-doc-svg (:issue/document issue) :subdomain nil)}]])

           (om/build issue-summary {:issue-id issue-id})


           ; [:div.single-issue-head
           ;  ; (om/build vote-box {:issue issue})
           ;  ; [:h4 (or title (:issue/title issue ""))]
           ;  (om/build vote-box {:issue issue})
           ;  [:div.single-issue-info
           ;  [:h4 (or title (:issue/title issue ""))]

           ;  ; [:div.issue-tags
           ;  ;  [:div.issue-tag.issue-type "feature"]
           ;  ;  [:div.issue-tag.issue-status "started"]
           ;  ;  [:div.issue-tag.issue-author (:issue/author issue)]
           ;  ;  ]

           ;  ; [:div.comment-foot
           ;  ;  [:a.issue-tag.issue-type {:role "button"} "feature"]
           ;  ;  [:a.issue-tag.issue-status {:role "button"} "started"]
           ;  ;  [:a.issue-tag.issue-author {:role "button"} (:issue/author issue)]
           ;  ;  ]

           ;  (issue-tags issue)

           ;  ; [:div.comment-author
           ;  ;  [:span.comment-avatar (common/icon :user)]
           ;  ;  [:span.comment-name (str " " (:issue/author issue))]
           ;  ;  [:span.comment-datetime (str " " (datetime/month-day (:issue/created-at issue)))]]

           ;  ]]

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

           ; [:div.single-issue-body

           ;  ; [:div.comment-author
           ;  ;  [:span.comment-avatar (common/icon :user)]
           ;  ;  [:span.comment-name (str " " (:issue/author issue))]
           ;  ;  [:span.comment-datetime (str " " (datetime/month-day (:issue/created-at issue)))]]

           ; (if description

           ;   [:div.comment-content
           ;    (or description (:issue/description issue ""))]

           ;   [:div.comment-content "There's no description yet."])]

           (when description
             [:div.issue-description (or description (:issue/description issue ""))]
             )

           ; [:div.single-issue-foot
           ;   [:a {:role "button"} "reply"]
           ;  ]

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

           [:div.content
            (om/build comment-form {:issue-id issue-id})]

           ; [:div.calls-to-action
           ; [:a.menu-button {:role "button"} "Comment"]]

           ; [:h4 (str "0" " comments")]

           (om/build comments {:issue issue})

           ; [:p "Make a new comment:"]

           ; (om/build comment-form {:issue-id issue-id})

           ])))))

(defn issue-list [_ owner]
  (reify
    om/IDisplayName (display-name [_] "Issue List")
    om/IInitState
    (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))
                     :rendered-issue-ids #{}
                     :all-issue-ids #{}
                     :render-time (datetime/server-date)})
    om/IDidMount
    (did-mount [_]
      (let [issue-db (om/get-shared owner :issue-db)
            cust-email (:cust/email (om/get-shared owner :cust))]
        (let [issue-ids (set (map :e (d/datoms @issue-db :aevt :issue/title)))]
          (om/set-state! owner :all-issue-ids issue-ids)
          (om/set-state! owner :rendered-issue-ids issue-ids))
        (fdb/add-attribute-listener
         issue-db
         :issue/title
         (om/get-state owner :listener-key)
         (fn [tx-report]
           (let [issue-ids (set (map :e (d/datoms @issue-db :aevt :issue/title)))]
             (if (empty? (om/get-state owner :rendered-issue-ids))
               (om/update-state! owner #(assoc % :rendered-issue-ids issue-ids :all-issue-ids issue-ids))
               (when cust-email
                 (om/update-state!
                  owner #(-> %
                           (assoc :all-issue-ids issue-ids)
                           (update-in [:rendered-issue-ids]
                                      (fn [r]
                                        (set/union r
                                                   (set (filter
                                                         (fn [i] (= cust-email
                                                                    (:issue/author (d/entity (:db-after tx-report) i))))
                                                         (set/difference issue-ids r)))))))))))))))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-attribute-listener (om/get-shared owner :issue-db)
                                     :issue/title
                                     (om/get-state owner :listener-key)))
    om/IRenderState
    (render-state [_ {:keys [all-issue-ids rendered-issue-ids render-time]}]
      (let [{:keys [cast! issue-db cust]} (om/get-shared owner)]
        (html
         [:div.menu-view.issues-list
           (let [deleted (set/difference rendered-issue-ids all-issue-ids)
                 added (set/difference all-issue-ids rendered-issue-ids)]
             (when (or (seq deleted) (seq added))
                [:a {:role "button"
                     :on-click #(om/update-state! owner (fn [s]
                                                          (assoc s
                                                                 :rendered-issue-ids (:all-issue-ids s)
                                                                 :render-time (datetime/server-date))))}
                 (cond (empty? deleted)
                       (str (count added) (if (< 1 (count added))
                                            " new issues were"
                                            " new issue was")
                            " added, click to refresh.")
                       (empty? added)
                       (str (count deleted) (if (< 1 (count deleted))
                                              " issues were"
                                              " issue was")
                            " removed, click to refresh.")
                       :else
                       (str (count added) (if (< 1 (count added))
                                            " new issues were"
                                            " new issue was")
                            " added, " (count deleted) " removed, click to refresh."))]))
           ; [:div.make {:key "issue-form"}
           ;  (om/build issue-form {})]
           [:div.content.make
           (om/build issue-form {})]
           ; :div.make {:key "summary"}
            (when-let [issues (seq (map #(d/entity @issue-db %) rendered-issue-ids))]
              (om/build-all issue-summary (map (fn [i] {:issue-id (:db/id i)})
                                               (sort (issue-model/issue-comparator cust render-time) issues))
                            {:key :issue-id
                             :opts {:issue-db issue-db}}))])))))

(defn issues* [app owner {:keys [submenu]}]
  (reify
    om/IDisplayName (display-name [_] "Issues")
    om/IInitState
    (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (fdb/setup-issue-listener!
       (om/get-shared owner :issue-db)
       (om/get-state owner :listener-key)
       (om/get-shared owner :comms)
       (om/get-shared owner :sente))
      (sente/subscribe-to-issues (om/get-shared owner :sente)
                                 (om/get-shared owner :comms)
                                 (om/get-shared owner :issue-db)))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :issue-db) (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (html
         (if submenu
           (om/build issue {:issue-id (:active-issue-id app)} {:key :issue-id})
           (om/build issue-list {} {:react-key "issue-list"}))))))

(defn issues [app owner {:keys [submenu]}]
  (reify
    om/IDisplayName (display-name [_] "Issues Overlay")
    om/IRender
    (render [_]
      (om/build issues* (select-keys app [:active-issue-id]) {:opts {:submenu submenu}}))))
