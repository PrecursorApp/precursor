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
         [:div.content.make
          [:form.adaptive {:on-submit #(do (utils/stop-event %)
                                         (when (seq issue-title)
                                           (let [fe-id (utils/squuid)]
                                             (d/transact! issue-db [{:db/id -1
                                                                     :issue/created-at (datetime/server-date)
                                                                     :issue/title issue-title
                                                                     :issue/author (:cust/email cust)
                                                                     :issue/document :none
                                                                     :frontend/issue-id fe-id}])
                                             (put! (om/get-shared owner [:comms :nav]) [:navigate! {:path (str "/issues/" fe-id)}]))
                                           (om/set-state! owner :issue-title "")))}
           [:textarea {:value issue-title
                       :required "true"
                       :disabled (when-not (utils/logged-in? owner) true)
                       :onChange #(om/set-state! owner :issue-title (.. % -target -value))}]
           [:label {:data-typing (gstring/format "Sounds good so far—%s characters left" (count issue-title))
                    :data-label "How can we improve Precursor?"}]
           [:input {:type "submit"
                    :value "Submit idea."
                    :disabled (when-not (utils/logged-in? owner) true)}]]])))))

(defn comment-form [{:keys [issue-id parent-id close-callback]} owner {:keys [issue-db]}]
  (reify
    om/IDisplayName (display-name [_] "Comment form")
    om/IInitState (init-state [_] {:comment-body ""})
    om/IRenderState
    (render-state [_ {:keys [comment-body]}]
      (let [issue-db (om/get-shared owner :issue-db)
            cust (om/get-shared owner :cust)]
        (html
         [:div.content.make
          [:form.adaptive {:on-submit #(do (utils/stop-event %)
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
             [:textarea {:required true
                         :value comment-body
                         :onChange #(om/set-state! owner :comment-body (.. % -target -value))}]
             [:label {:data-label "What do you think?"
                      :data-forgot "To be continued"}]
             [:input {:type "submit"
                      :value "Add comment."}]]])))))

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
           [:div.content {:class (when-not (:issue/description issue) " make ")}
            [:form.adaptive {:on-submit submit}
             [:textarea.issue-description {:class (when (:issue/description issue) " to-edit ")
                                           :value (or issue-description (:issue/description issue ""))
                                           :required "true"
                                           :on-change #(om/set-state! owner :issue-description (.. % -target -value))
                                           :on-blur submit}]
             [:label {:data-label "Issue Description"
                      :data-placeholder "Want to elaborate on your idea?"}]
             [:p.issue-foot
              [:span (common/icon :user) " "]
              [:a {:role "button"} (:issue/author issue)]
              [:span " on "]
              [:a {:role "button"} (datetime/month-day (:issue/created-at issue))]
              [:span " — "]
              (when editable?
                [:a.issue-description-edit {:role "button"
                                            :key "Cancel"
                                            :on-click submit}
                 "Save"])]]]

           [:div.comment {:class (when-not (om/get-state owner :to-not-edit?) " make ")}
            [:p.issue-description {:class (when (om/get-state owner :to-not-edit?) " to-not-edit ")}
             (if (:issue/description issue)
               (:issue/description issue)

               (if editable?
                 [:a {:role "button"
                      :on-click #(do
                                   (om/set-state! owner :editing? true)
                                   (om/set-state! owner :to-not-edit? true))}
                  [:span (common/icon :plus)]
                  [:span " Add a description."]]

                 [:span "No description yet."]))]
            [:p.issue-foot
             [:span (common/icon :user) " "]
             [:a {:role "button"} (:issue/author issue)]
             [:span " on "]
             [:a {:role "button"} (datetime/month-day (:issue/created-at issue))]
             [:span " — "]
             (when editable?
               [:a.issue-description-edit {:role "button"
                                           :key "Edit"
                                           :on-click #(do
                                                        (om/set-state! owner :editing? true)
                                                        (om/set-state! owner :to-not-edit? true))}
                "Edit"])]]))))))

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

(defn single-comment [{:keys [comment-id issue-id rendered-comment-ids]} owner {:keys [ancestors]
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
            comment (d/entity @issue-db comment-id)
            child-ids (set/intersection rendered-comment-ids (issue-model/direct-descendants @issue-db comment))]
        (html
         [:div.comment.make
          [:div.issue-divider]
          [:p (:comment/body comment)]
          [:p.issue-foot
           [:span (common/icon :user) " "]
           [:a {:role "button"} (:comment/author comment)]
           [:span " on "]
           [:a {:role "button"} (datetime/month-day (:comment/created-at comment))]
           [:span " — "]
           [:a {:role "button"
                :on-click #(do
                             (if (om/get-state owner :replying?)
                               (om/set-state! owner :replying? false)
                               (om/set-state! owner :replying? true)))}
            (if (om/get-state owner :replying?) "Cancel" "Reply")]]

          (when (om/get-state owner :replying?)
            (om/build comment-form {:issue-id issue-id
                                    :parent-id comment-id
                                    :close-callback #(om/set-state! owner :replying? false)}))
          (when (and (not (contains? ancestors (:db/id comment))) ; don't render cycles
                     (pos? (count child-ids)))
            [:div.comments {:key "child-comments"}
             (for [id child-ids]
               (om/build single-comment {:issue-id issue-id
                                         :comment-id id
                                         :rendered-comment-ids rendered-comment-ids}
                         {:key :comment-id
                          :opts {:ancestors (conj ancestors (:db/id comment))}}))])])))))


(defn comments [{:keys [issue-id rendered-comment-ids]} owner]
  (reify
    om/IDisplayName (display-name [_] "Comments")
    om/IRender
    (render [_]
      (let [{:keys [issue-db]} (om/get-shared owner)
            comment-ids (issue-model/top-level-comment-ids @issue-db issue-id)]
        (html
         [:div.comments
          (for [id (set/intersection comment-ids rendered-comment-ids)]
            (om/build single-comment {:comment-id id
                                      :issue-id issue-id
                                      :rendered-comment-ids rendered-comment-ids}
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
            [:a {:role "button"} (str comment-count " comment" (when (not= 1 comment-count) "s"))]
            [:span " for "]
            [:a {:role "button"} "bugfix"]
            [:span " in "]
            [:a {:role "button"} "development."]]]
          (om/build vote-box {:issue issue})])))))

(defn issue* [{:keys [issue-id]} owner]
  (reify
    om/IDisplayName (display-name [_] "Issue")
    om/IInitState
    (init-state [_] {:listener-key (.getNextUniqueId (.getInstance IdGenerator))
                     :title nil
                     :description nil
                     :rendered-comment-ids #{}
                     :all-comment-ids #{}})
    om/IDidMount
    (did-mount [_]
      (let [issue-db (om/get-shared owner :issue-db)
            comment-ids (set (map :v (d/datoms @issue-db :aevt :issue/comments issue-id)))
            cust-email (:cust/email (om/get-shared owner :cust))]
        (om/set-state! owner :all-comment-ids comment-ids)
        (om/set-state! owner :rendered-comment-ids comment-ids)
        (fdb/add-entity-listener issue-db
                                 issue-id
                                 (om/get-state owner :listener-key)
                                 (fn [tx-report]
                                   (let [comment-ids (set (map :v (d/datoms @issue-db :aevt :issue/comments issue-id)))]
                                     (if (empty? (om/get-state owner :rendered-comment-ids))
                                       (om/update-state! owner #(assoc % :rendered-comment-ids comment-ids :all-comment-ids comment-ids))
                                       (when cust-email
                                         (om/update-state!
                                          owner #(-> %
                                                   (assoc :all-comment-ids comment-ids)
                                                   (update-in [:rendered-comment-ids]
                                                              (fn [r]
                                                                (set/union r
                                                                           (set (filter
                                                                                 (fn [i] (= cust-email
                                                                                            (:comment/author (d/entity (:db-after tx-report) i))))
                                                                                 (set/difference comment-ids r)))))))))))
                                   (om/refresh! owner)))))
    om/IWillUnmount
    (will-unmount [_]
      (fdb/remove-entity-listener (om/get-shared owner :issue-db)
                                  issue-id
                                  (om/get-state owner :listener-key)))
    om/IRenderState
    (render-state [_ {:keys [title description all-comment-ids rendered-comment-ids]}]
      (let [{:keys [cast! issue-db]} (om/get-shared owner)
            issue (ds/touch+ (d/entity @issue-db issue-id))]
        (html
         [:div.menu-view.issue
          [:div.issue-summary
           (om/build issue-card {:issue-id issue-id})
           (om/build description-form {:issue issue :issue-id issue-id})]

          [:div.issue-comments
           (om/build comment-form {:issue-id issue-id} {:react-key "comment-form"})
           (let [deleted (set/difference rendered-comment-ids all-comment-ids)
                 added (set/difference all-comment-ids rendered-comment-ids)]
             (when (or (seq deleted) (seq added))
               [:div.make {:key "new-comments-notice"}
                [:a {:role "button"
                     :on-click #(om/update-state! owner (fn [s]
                                                          (assoc s
                                                                 :rendered-comment-ids (:all-comment-ids s))))}
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
                            " added, " (count deleted) " removed, click to refresh."))]]))
           (om/build comments {:issue-id issue-id :rendered-comment-ids rendered-comment-ids}
                     {:react-key "issue-comments"})]])))))

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
          (om/build issue-form {})
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
         (om/build issue {:issue-uuid (:active-issue-uuid app)} {:key :issue-uuid})
         (om/build issue-list {} {:react-key "issue-list"}))))))

(defn issues [app owner {:keys [submenu]}]
  (reify
    om/IDisplayName (display-name [_] "Issues Overlay")
    om/IRender
    (render [_]
      (om/build issues* (select-keys app [:active-issue-uuid]) {:opts {:submenu submenu}}))))
