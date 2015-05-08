(ns frontend.components.issues
  (:require [cljs.core.async :as async :refer [put!]]
            [cljs-time.core :as time]
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
            [frontend.utils.ajax :as ajax]
            [frontend.utils :as utils]
            [goog.string :as gstring]
            [goog.string.format]
            [om.core :as om]
            [om.dom :as dom])
  (:require-macros [sablono.core :refer (html)]
                   [cljs.core.async.macros :as am :refer [go]])
  (:import [goog.ui IdGenerator]))

(defn issue-form [_ owner]
  (reify
    om/IDisplayName (display-name [_] "Issue form")
    om/IInitState (init-state [_] {:issue-title ""})
    om/IRenderState
    (render-state [_ {:keys [issue-title submitting?]}]
      (let [{:keys [issue-db cust cast!]} (om/get-shared owner)]
        (html
         [:form.issue-form {:on-submit #(do (utils/stop-event %)
                                            (go
                                              (try
                                                (om/set-state! owner :submitting? true)
                                                (when (seq issue-title)
                                                  (let [fe-id (utils/squuid)
                                                        doc-id (let [result (async/<! (ajax/managed-ajax :post "/api/v1/document/new"
                                                                                                         :params {:read-only true}))]
                                                                 (if (= :success (:status result))
                                                                   (get-in result [:document :db/id])
                                                                   ;; something went wrong, notifying error channel
                                                                   (do (async/put! (om/get-shared owner [:comms :errors]) [:api-error result])
                                                                       (throw "Couldn't create doc."))))]
                                                    (d/transact! issue-db [{:db/id -1
                                                                            :issue/created-at (datetime/server-date)
                                                                            :issue/title issue-title
                                                                            :issue/author (:cust/email cust)
                                                                            :issue/document doc-id
                                                                            :frontend/issue-id fe-id}])
                                                    (put! (om/get-shared owner [:comms :nav]) [:navigate! {:path (str "/issues/" fe-id)}]))
                                                  (om/set-state! owner :issue-title ""))
                                                (finally
                                                  (om/set-state! owner :submitting? false)))))}
          [:div.adaptive
           [:textarea {:value issue-title
                       :required "true"
                       :disabled (or submitting? (not (utils/logged-in? owner)))
                       :onChange #(om/set-state! owner :issue-title (.. % -target -value))}]
           [:label {:data-typing (gstring/format "Sounds good so far—%s characters left" (count issue-title))
                    :data-label (if (utils/logged-in? owner)
                                  "How can we improve Precursor?"
                                  "Sign in to make an issue.")}]]
          [:div.menu-buttons
           (if (utils/logged-in? owner)
             [:input.menu-button {:type "submit"
                                  :value (if submitting?
                                           "Submitting..."
                                           "Submit idea.")
                                  :disabled (when-not (utils/logged-in? owner) true)}]

             (om/build common/google-login {:source "Issue form"}))]])))))

(defn comment-form [{:keys [issue-id parent-id close-callback]} owner {:keys [issue-db]}]
  (reify
    om/IDisplayName (display-name [_] "Comment form")
    om/IInitState (init-state [_] {:comment-body ""})
    om/IRenderState
    (render-state [_ {:keys [comment-body]}]
      (let [issue-db (om/get-shared owner :issue-db)
            cust (om/get-shared owner :cust)]
        (html
         [:form.comment-form {:on-submit #(do (utils/stop-event %)
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
                                            (when (fn? close-callback)
                                              (close-callback)))}
          [:div.adaptive
           [:textarea {:required true
                       :value comment-body
                       :disabled (when-not (utils/logged-in? owner) true)
                       :onChange #(om/set-state! owner :comment-body (.. % -target -value))}]
           [:label {:data-label (if (utils/logged-in? owner)
                                  "What do you think?"
                                  "Sign in to comment on this issue.")
                    :data-forgot "To be continued"}]]
          [:div.menu-buttons
           (if (utils/logged-in? owner)
             [:input.menu-button {:type "submit"
                                  :value "Add comment."
                                  :disabled (when-not (utils/logged-in? owner) true)}]

             (om/build common/google-login {:source "Comment form"}))]])))))

(defn description-form [{:keys [issue issue-id]} owner]
  (reify
    om/IDisplayName (display-name [_] "Description form")
    om/IInitState (init-state [_] {:issue-description nil
                                   :editing? false
                                   :to-not-edit? false})
    om/IRenderState
    (render-state [_ {:keys [issue-description]}]
      (let [{:keys [issue-db cust cast!]} (om/get-shared owner)
            editable? (= (:cust/email cust) (:issue/author issue))
            editing? (and editable? (om/get-state owner :editing?))
            submit #(do (utils/stop-event %)
                        (when issue-description
                          (d/transact! issue-db [{:db/id (:db/id issue)
                                                  :issue/description issue-description}]))
                        (om/set-state! owner :issue-description nil)
                        (om/set-state! owner :editing? false))]
        (html
         (if editing?
           [:form.issue-description-form {:on-submit submit}
            [:div.adaptive.issue-description-in ;{:class (when (:issue/description issue) " to-edit ")}
             [:textarea {;:class (when (:issue/description issue) " to-edit ")
                         :value (or issue-description (:issue/description issue ""))
                         :required "true"
                         :on-change #(om/set-state! owner :issue-description (.. % -target -value))
                         :on-blur submit}]
             [:label {:data-label "Description"
                      :data-placeholder "+ Add a description."}]]
            [:p.comment-foot
             ;; hide avatars for now
             ;; [:span (common/icon :user) " "]
             [:span (:issue/author issue)]
             [:span " on "]
             [:span (datetime/month-day (:issue/created-at issue))]
             (when editable?
               (list
                 [:span.pre "  •  "]
                 [:a.issue-description-edit {:role "button"
                                             :key "Cancel"
                                             :on-click submit}
                  "Save"]))]]

           [:div.comment {:class (when-not (om/get-state owner :to-not-edit?) " make ")}
            [:div.issue-description-out {:class (when (om/get-state owner :to-not-edit?) " to-not-edit ")}
             (if (:issue/description issue)
               (:issue/description issue)

               (if editable?
                 [:a {:role "button"
                      :on-click #(do
                                   (om/set-state! owner :editing? true)
                                   (om/set-state! owner :to-not-edit? true))}
                  [:span "+ Add a description."]]

                 [:span "No description yet."]))]
            [:p.comment-foot
             ;; hide avatars for now
             ;; [:span (common/icon :user) " "]
             [:span (:issue/author issue)]
             [:span " on "]
             [:span (datetime/month-day (:issue/created-at issue))]
             (when editable?
               (list
                 [:span.pre "  •  "]
                 [:a.issue-description-edit {:role "button"
                                             :key "Edit"
                                             :on-click #(do
                                                          (om/set-state! owner :editing? true)
                                                          (om/set-state! owner :to-not-edit? true))}
                  "Edit"]))]]))))))

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
         [:div.issue-vote (merge {:role "button"
                                  :class (if voted? " voted " " novote ")}
                                 (when-not voted?
                                   {:on-click #(d/transact! issue-db
                                                            [{:db/id (:db/id issue)
                                                              :issue/votes {:db/id -1
                                                                            :frontend/issue-id (utils/squuid)
                                                                            :vote/cust (:cust/email cust)}}])}))
          [:div.issue-votes {:key (count (:issue/votes issue))}
           (count (:issue/votes issue))]
          [:div.issue-upvote
           (common/icon :north)]])))))

(defn unrendered-comments-notice [all-ids rendered-ids refresh-callback]
  (let [added (set/difference all-ids rendered-ids)
        deleted (set/difference rendered-ids all-ids)]
    (when (or (seq deleted) (seq added))
      [:div.make {:key "new-comments-notice"}
       [:a {:role "button"
            :on-click refresh-callback}
        (cond (empty? deleted)
              (str (count added) (if (< 1 (count added))
                                   " new comments were"
                                   " new comment was")
                   " added, click to refresh.")
              (empty? added)
              (str (count deleted) (if (< 1 (count deleted))
                                     " comments were"
                                     " comment was")
                   " removed, click to refresh.")
              :else
              (str (count added) (if (< 1 (count added))
                                   " new comments were"
                                   " new comment was")
                   " added, " (count deleted) " removed, click to refresh."))]])))

(defn single-comment [{:keys [comment-id issue-id]} owner {:keys [ancestors]
                                                           :or {ancestors #{}}}]
  (reify
    om/IDisplayName (display-name [_] "Single comment")
    om/IInitState
    (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))
                     :replying? false
                     :all-child-ids #{}
                     :rendered-child-ids #{}})
    om/IDidMount
    (did-mount [_]
      (let [issue-db (om/get-shared owner :issue-db)
            cust-email (:cust/email (om/get-shared owner :cust))]
        (let [child-ids (issue-model/direct-descendant-ids @issue-db comment-id)]
          (om/set-state! owner :all-child-ids child-ids)
          (om/set-state! owner :rendered-child-ids child-ids))
        (fdb/add-entity-listener issue-db comment-id (om/get-state owner :listener-key)
                                 (fn [tx-report]
                                   (om/refresh! owner)))
        (fdb/add-attribute-listener issue-db
                                    :comment/parent
                                    (om/get-state owner :listener-key)
                                    (fn [tx-report]
                                      (when (first (filter #(= comment-id (:v %))
                                                           (:tx-data tx-report)))
                                        (let [child-ids (issue-model/direct-descendant-ids @issue-db comment-id)]
                                          (om/update-state! owner
                                                            #(-> %
                                                               (assoc :all-child-ids child-ids)
                                                               (update-in [:rendered-child-ids]
                                                                          (fn [r]
                                                                            (set/union (set/intersection r child-ids)
                                                                                       (set (filter
                                                                                             (fn [i] (= cust-email (:comment/author (d/entity (:db-after tx-report) i))))
                                                                                             (set/difference child-ids r))))))))))))))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-entity-listener (om/get-shared owner :issue-db)
                                  comment-id
                                  (om/get-state owner :listener-key))
      (fdb/remove-attribute-listener (om/get-shared owner :issue-db)
                                     :comment/parent
                                     (om/get-state owner :listener-key)))
    om/IRenderState
    (render-state [_ {:keys [replying? all-child-ids rendered-child-ids]}]
      (let [{:keys [issue-db cast!]} (om/get-shared owner)
            comment (d/entity @issue-db comment-id)]
        (html
         [:div.comment.make {:key comment-id}
          [:div.issue-divider]
          [:div.comment-body (:comment/body comment)]
          [:p.comment-foot
           ;; hide avatars for now
           ;; [:span (common/icon :user) " "]
           [:span (:comment/author comment)]
           [:span " on "]
           [:span (datetime/month-day (:comment/created-at comment))]
           (when (utils/logged-in? owner)
             (list
               [:span.pre "  •  "]
               [:a {:role "button"
                    :on-click #(do
                                 (if (om/get-state owner :replying?)
                                   (om/set-state! owner :replying? false)
                                   (om/set-state! owner :replying? true)))}
                (if (om/get-state owner :replying?) "Cancel" "Reply")]))]

          (when (om/get-state owner :replying?)
            [:div.content.make
             (om/build comment-form {:issue-id issue-id
                                     :parent-id comment-id
                                     :close-callback #(om/set-state! owner :replying? false)}
                       {:react-key "comment-form"})])
          (unrendered-comments-notice all-child-ids rendered-child-ids #(om/update-state! owner (fn [s]
                                                                                                  (assoc s :rendered-child-ids (:all-child-ids s)))))
          (when (and (not (contains? ancestors (:db/id comment))) ; don't render cycles
                     (pos? (count rendered-child-ids)))
            [:div.comments {:key "child-comments"}
             (for [id rendered-child-ids]
               (om/build single-comment {:issue-id issue-id
                                         :comment-id id}
                         {:key :comment-id
                          :opts {:ancestors (conj ancestors (:db/id comment))}}))])])))))


(defn comments [{:keys [issue-id]} owner]
  (reify
    om/IDisplayName (display-name [_] "Comments")
    om/IInitState
    (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))
                     :replying? false
                     :all-top-level-ids #{}
                     :rendered-top-level-ids #{}})
    om/IDidMount
    (did-mount [_]
      (let [issue-db (om/get-shared owner :issue-db)
            cust-email (:cust/email (om/get-shared owner :cust))]
        (let [top-level-ids (issue-model/top-level-comment-ids @issue-db issue-id)]
          (om/set-state! owner :all-top-level-ids top-level-ids)
          (om/set-state! owner :rendered-top-level-ids top-level-ids))
        (fdb/add-entity-listener issue-db
                                 issue-id
                                 (om/get-state owner :listener-key)
                                 (fn [tx-report]
                                   (when (first (filter #(= :issue/comments (:a %))
                                                        (:tx-data tx-report)))
                                     (let [top-level-ids (issue-model/top-level-comment-ids @issue-db issue-id)]
                                       (om/update-state! owner
                                                         #(-> %
                                                            (assoc :all-top-level-ids top-level-ids)
                                                            (update-in [:rendered-top-level-ids]
                                                                       (fn [r]
                                                                         (if (empty? r)
                                                                           top-level-ids
                                                                           (set/union (set/intersection r top-level-ids)
                                                                                      (set (filter
                                                                                            (fn [i] (= cust-email (:comment/author (d/entity (:db-after tx-report) i))))
                                                                                            (set/difference top-level-ids r)))))))))))))))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-entity-listener (om/get-shared owner :issue-db)
                                  issue-id
                                  (om/get-state owner :listener-key)))
    om/IRenderState
    (render-state [_ {:keys [all-top-level-ids rendered-top-level-ids]}]
      (let [{:keys [issue-db]} (om/get-shared owner)]
        (html
         [:div.comments {:key issue-id}
          (unrendered-comments-notice all-top-level-ids rendered-top-level-ids
                                      #(om/update-state! owner (fn [s]
                                                                 (assoc s :rendered-top-level-ids (:all-top-level-ids s)))))
          (for [id rendered-top-level-ids]
            (om/build single-comment {:comment-id id
                                      :issue-id issue-id}
                      {:key :comment-id}))])))))

(defn issue-card [{:keys [issue-id]} owner]
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
         [:div.issue-card.content.make
          [:div.issue-info
           [:a.issue-title {:href (urls/issue-url issue)
                            :target "_top"}
            (:issue/title issue)]
           [:p.issue-foot
            [:span (str comment-count " comment" (when (not= 1 comment-count) "s"))]
            [:span " for "]
            [:span "bugfix"]
            [:span " in "]
            [:span "development."]]]
          (om/build vote-box {:issue issue})])))))

(defn issue* [{:keys [issue-id]} owner]
  (reify
    om/IDisplayName (display-name [_] "Issue")
    om/IInitState
    (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))
                     :title nil
                     :description nil})
    om/IDidMount
    (did-mount [_]
      (let [issue-db (om/get-shared owner :issue-db)]
        (fdb/add-entity-listener issue-db
                                 issue-id
                                 (om/get-state owner :listener-key)
                                 (fn [tx-report]
                                   (om/refresh! owner)))))
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
          [:div.issue-summary {:key "summary"}
           (om/build issue-card {:issue-id issue-id})
           (om/build description-form {:issue issue :issue-id issue-id})]

          [:div.issue-comments {:key "issue-comments"}
           [:div.content.make
            (om/build comment-form {:issue-id issue-id} {:react-key "comment-form"})]
           (om/build comments {:issue-id issue-id} {:react-key "comments"})]])))))

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
          [:div.content.make
           (om/build issue-form {})]
          (when-let [issues (seq (map #(d/entity @issue-db %) rendered-issue-ids))]
            (om/build-all issue-card (map (fn [i] {:issue-id (:db/id i)})
                                          (sort (issue-model/issue-comparator cust render-time) issues))
                          {:key :issue-id
                           :opts {:issue-db issue-db}}))])))))

(defn issue [{:keys [issue-uuid]} owner]
  (reify
    om/IDisplayName (display-name [_] "Issue Wrapper")
    om/IInitState
    (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (fdb/add-attribute-listener (om/get-shared owner :issue-db)
                                  :frontend/issue-id
                                  (om/get-state owner :listener-key)
                                  (fn [tx-report]
                                    (om/refresh! owner))))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-attribute-listener (om/get-shared owner :issue-db)
                                     :frontend/issue-id
                                     (om/get-state owner :listener-key)))
    om/IRender
    (render [_]
      (let [issue-id (:e (first (d/datoms @(om/get-shared owner :issue-db) :avet :frontend/issue-id issue-uuid)))]
        (if issue-id
          (om/build issue* {:issue-id issue-id})
          (dom/div #js {:className "loading"} "Loading..."))))))

(defn issues* [app owner {:keys [submenu]}]
  (reify
    om/IDisplayName (display-name [_] "Issues")
    om/IInitState
    (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (sente/subscribe-to-issues (om/get-shared owner :sente)
                                 (om/get-shared owner :comms)
                                 (om/get-shared owner :issue-db)))
    om/IRender
    (render [_]
      (html
       (if submenu
         (om/build issue {:issue-uuid (:active-issue-uuid app)} {:key :issue-uuid})
         (om/build issue-list {} {:react-key "issue-list"}))))))

(defn issues [app owner {:keys [submenu]}]
  (reify
    om/IDisplayName (display-name [_] "Issues Overlay")
    om/IRender
    (render [_]
      (om/build issues* (select-keys app [:active-issue-uuid]) {:opts {:submenu submenu}}))))
