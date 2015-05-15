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
            [goog.dom]
            [goog.dom.forms :as gforms]
            [goog.dom.Range]
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
    om/IInitState (init-state [_] {:issue-title ""
                                   :input-height 64})
    om/IRenderState
    (render-state [_ {:keys [issue-title submitting? input-height]}]
      (let [{:keys [issue-db cust cast!]} (om/get-shared owner)
            char-limit 100
            chars-left (- char-limit (count issue-title))
            input-disabled? (or submitting?
                                (not (utils/logged-in? owner))
                                (neg? chars-left))]
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
                                                                            :issue/title (gstring/normalizeWhitespace (str/trim issue-title))
                                                                            :issue/author (:cust/uuid cust)
                                                                            :issue/document doc-id
                                                                            :frontend/issue-id fe-id}])
                                                    (d/transact! issue-db [{:frontend/issue-id fe-id
                                                                            :issue/votes {:db/id -1
                                                                                          :frontend/issue-id (utils/squuid)
                                                                                          :vote/cust (:cust/uuid cust)}}])
                                                    (put! (om/get-shared owner [:comms :nav]) [:navigate! {:path (str "/issues/" fe-id)}]))
                                                  (om/set-state! owner :issue-title "")
                                                  (om/set-state! owner :input-height 64))
                                                (finally
                                                  (om/set-state! owner :submitting? false)))))}
          [:div.adaptive
           [:textarea (merge {:value issue-title
                              :style {:height input-height}
                              :required "true"
                              :disabled (or submitting? (not (utils/logged-in? owner)))
                              :onChange #(do (om/set-state! owner :issue-title (.. % -target -value))
                                             (let [node (.-target %)]
                                               (when (not= (.-scrollHeight node) (.-clientHeight node))
                                                 (om/set-state! owner :input-height (max 64 (.-scrollHeight node))))))}
                             (when (neg? chars-left)
                               {:data-warning "true"}))]
           [:label (merge {:data-label (if (utils/logged-in? owner)
                                         "How can we improve Precursor?"
                                         "Sign in to make an issue.")}
                          (if (neg? chars-left)
                            {:data-warning (gstring/format "%s character%s too many"
                                                           (- chars-left)
                                                           (if (= -1 chars-left) "" "s"))}
                            {:data-typing (gstring/format "Sounds good so far; %s character%s left"
                                                          chars-left
                                                          (if (= 1 chars-left) "" "s"))}))]]
          [:div.menu-buttons
           (if (utils/logged-in? owner)
             [:input.menu-button {:type "submit"
                                  :value (if submitting?
                                           "Submitting..."
                                           "Submit idea.")
                                  :disabled input-disabled?}]

             (om/build common/google-login {:source "Issue form"}))]])))))

(defn comment-form [{:keys [issue-id parent-id close-callback]} owner {:keys [issue-db]}]
  (reify
    om/IDisplayName (display-name [_] "Comment form")
    om/IInitState (init-state [_] {:comment-body ""
                                   :input-height 64})
    om/IRenderState
    (render-state [_ {:keys [comment-body input-height]}]
      (let [issue-db (om/get-shared owner :issue-db)
            cust (om/get-shared owner :cust)]
        (html
         [:form.comment-form {:on-submit #(do (utils/stop-event %)
                                              (when (seq comment-body)
                                                (d/transact! issue-db [{:db/id issue-id
                                                                        :issue/comments (merge {:db/id -1
                                                                                                :comment/created-at (datetime/server-date)
                                                                                                :comment/body (str/trim comment-body)
                                                                                                :comment/author (:cust/uuid cust)
                                                                                                :frontend/issue-id (utils/squuid)}
                                                                                               (when parent-id
                                                                                                 {:comment/parent parent-id}))}])
                                                (om/set-state! owner :comment-body "")
                                                (om/set-state! owner :input-height 64))
                                              (when (fn? close-callback)
                                                (close-callback)))}
          [:div.adaptive
           [:textarea {:required true
                       :style {:height input-height}
                       :value comment-body
                       :disabled (when-not (utils/logged-in? owner) true)
                       :onChange #(do (om/set-state! owner :comment-body (.. % -target -value))
                                      (let [node (.-target %)]
                                        (when (not= (.-scrollHeight node) (.-clientHeight node))
                                          (om/set-state! owner :input-height (max 64 (.-scrollHeight node))))))}]
           [:label {:data-label (if (utils/logged-in? owner)
                                  "What do you think?"
                                  "Sign in to add comments.")
                    :data-forgot "To be continued"}]]
          [:div.menu-buttons
           (if (utils/logged-in? owner)
             [:input.menu-button {:type "submit"
                                  :value "Add comment."
                                  :disabled (when-not (utils/logged-in? owner) true)}]

             (om/build common/google-login {:source "Comment form"}))]])))))

(defn cust-name-form [{:keys [cust-name]} owner]
  (reify
    om/IDisplayName (display-name [_] "Cust name form")
    om/IInitState (init-state [_] {:editing? true :editing-name nil})
    om/IDidUpdate
    (did-update [_ _ prev-state]
      (when (and (not (:editing? prev-state))
                 (om/get-state owner :editing?)
                 (om/get-node owner "name-input"))
        (.focus (om/get-node owner "name-input"))
        (.select (goog.dom.Range/createFromNodeContents (om/get-node owner "name-input")))))
    om/IRenderState
    (render-state [_ {:keys [editing? editing-name]}]
      (let [cast! (om/get-shared owner :cast!)
            reset-form (fn []
                         (om/set-state! owner :editing? false)
                         (om/set-state! owner :editing-name nil))
            submit-form (fn []
                          ;; be sure to pull it out of the state, since we're not
                          ;; re-rendering on change
                          (when (seq (om/get-state owner :editing-name))
                            (cast! :self-updated {:name (om/get-state owner :editing-name)}))
                          ;; This is a terrible hack, should find a way to wait for the cast!
                          ;; to complete :(
                          (js/setTimeout #(reset-form) 0))]
        (html
         [:span
          [:span {:contentEditable editing?
                  :onClick #(do (om/set-state! owner :editing? true)
                                (om/set-state! owner :editing-name (or cust-name "")))
                  :class (str "issue-name-input " (when-not (seq cust-name) "empty "))
                  :type "text"
                  :ref "name-input"
                  :spell-check false
                  :value (or editing-name cust-name "")
                  :onBlur #(submit-form)
                  ;;:onChange #(om/set-state! owner :editing-name (.. % -target -value))
                  :onInput #(om/set-state-nr! owner :editing-name (goog.dom/getRawTextContent (.-target %)))
                  :onKeyDown #(cond (= "Enter" (.-key %)) (do
                                                            (utils/stop-event %)
                                                            (submit-form))
                                    (= "Escape" (.-key %)) (reset-form))}
           (or editing-name (if (seq cust-name)
                              cust-name
                              "+ Add your name"))]
          [:span " on"]])))))

(defn author-byline
  "Shows author's name or empty span
  If current cust authored the post and has no name, shows a set name form"
  [{:keys [author-uuid uuid->cust]} owner]
  (reify
    om/IDisplayName (display-name [_] "Author byline")
    om/IRender
    (render [_]
      (let [cust-name (get-in uuid->cust [author-uuid :cust/name])]
        (html
         (if (= author-uuid (om/get-shared owner [:cust :cust/uuid]))
           (om/build cust-name-form {:cust-name cust-name})
           (if (seq cust-name)
             [:span [:span (str cust-name)] [:span " on"]]
             [:span])))))))

(defn description-form [{:keys [issue issue-id uuid->cust]} owner]
  (reify
    om/IDisplayName (display-name [_] "Description form")
    om/IInitState (init-state [_] {:issue-description nil
                                   :editing? nil})
    om/IDidUpdate
    (did-update [_ _ prev-state]
      (when (and (not (:editing? prev-state))
                 (om/get-state owner :editing?)
                 (om/get-node owner "description-input"))
        (gforms/focusAndSelect (om/get-node owner "description-input"))))
    om/IRenderState
    (render-state [_ {:keys [issue-description]}]
      (let [{:keys [issue-db cust cast!]} (om/get-shared owner)
            editable? (= (:cust/uuid cust) (:issue/author issue))
            editing? (and editable? (om/get-state owner :editing?))
            clear-form #(om/update-state! owner (fn [s] (assoc s :issue-description nil :editing? false)))
            submit #(do (utils/stop-event %)
                        (when issue-description
                          (d/transact! issue-db [{:db/id (:db/id issue)
                                                  :issue/description (str/trim issue-description)}]))
                        (clear-form))]
        (html
         (if editing?
           [:form.issue-description-form {:on-submit submit}
            [:div.adaptive.issue-description-in
             [:textarea {:ref "description-input"
                         :value (or issue-description (:issue/description issue ""))
                         :required "true"
                         :on-key-down #(if (= "Escape" (.-key %))
                                         (clear-form)
                                         true)
                         :on-change #(om/set-state! owner :issue-description (.. % -target -value))
                         :on-blur submit}]
             [:label {:data-label "Description"
                      :data-placeholder "+ Add a description."}]]
            [:p.comment-foot
             ;; hide avatars for now
             ;; [:span (common/icon :user) " "]
             (when (:issue/description issue)
               (list
                (om/build author-byline {:author-uuid (:issue/author issue) :uuid->cust uuid->cust})
                [:span " "]
                [:span (datetime/month-day (:issue/created-at issue))]))
             (when editable?
               (list
                [:span.pre "  •  "]
                [:a.issue-description-edit {:role "button"
                                            :key "Cancel"
                                            :on-click submit}
                 [:span "Save"]]))]]

           ;; Don't show sliding animation when the description replaces the form after an edit
           [:div.comment
            [:div.issue-description-out {:class (when (nil? (om/get-state owner :editing?)) " to-not-edit ")}
             (if (:issue/description issue)
               (:issue/description issue)

               (if editable?
                 [:a {:role "button"
                      :on-click #(om/set-state! owner :editing? true)}
                  [:span "+ Add a description."]]

                 [:span "No description yet."]))]
            [:p.comment-foot
             ;; hide avatars for now
             ;; [:span (common/icon :user) " "]
             (when (:issue/description issue)
               (list
                (om/build author-byline {:author-uuid (:issue/author issue) :uuid->cust uuid->cust})
                [:span " "]
                [:span (datetime/month-day (:issue/created-at issue))]))
             (when editable?
               (list
                [:span.pre "  •  "]
                [:a.issue-description-edit {:role "button"
                                            :key "Edit"
                                            :on-click #(om/set-state! owner :editing? true)}
                 [:span "Edit"]]))]]))))))

(defn vote-box [{:keys [issue]} owner]
  (reify
    om/IDisplayName (display-name [_] "Vote box")
    om/IRender
    (render [_]
      (let [{:keys [cast! issue-db cust]} (om/get-shared owner)
            can-vote? (utils/logged-in? owner)
            voted? (when can-vote?
                     (d/q '[:find ?e .
                            :in $ ?issue-id ?uuid
                            :where
                            [?issue-id :issue/votes ?e]
                            [?e :vote/cust ?uuid]]
                          @issue-db (:db/id issue) (:cust/uuid cust)))]
        (html
         [:div.issue-vote (merge {:role "button"
                                  :class (when can-vote? (if voted? " voted " " novote "))}
                                 (when-not can-vote?
                                   {:title "Please log in to vote."})
                                 (when (and (not voted?) can-vote?)
                                   {:on-click #(d/transact! issue-db
                                                            [{:db/id (:db/id issue)
                                                              :issue/votes {:db/id -1
                                                                            :frontend/issue-id (utils/squuid)
                                                                            :vote/cust (:cust/uuid cust)}}])}))
          [:div.issue-votes {:key (count (:issue/votes issue))}
           (count (:issue/votes issue))]
          [:div.issue-upvote
           (common/icon :north)]])))))

(defn unrendered-comments-notice [all-ids rendered-ids refresh-callback]
  (let [added (set/difference all-ids rendered-ids)
        deleted (set/difference rendered-ids all-ids)]
    (when (or (seq deleted) (seq added))
      [:a.issue-missing {:role "button"
                         :key "new-comments-notice"
                         :on-click refresh-callback}
       [:span
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

(defn single-comment [{:keys [comment-id issue-id uuid->cust]} owner {:keys [ancestors]
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
            cust-uuid (:cust/uuid (om/get-shared owner :cust))]
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
                                                                                             (fn [i] (= cust-uuid (:comment/author (d/entity (:db-after tx-report) i))))
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
         [:div.comment {:key comment-id}
          [:div.issue-divider]
          [:div.comment-body (:comment/body comment)]
          [:p.comment-foot
           ;; hide avatars for now
           ;; [:span (common/icon :user) " "]
           (om/build author-byline {:author-uuid (:comment/author comment) :uuid->cust uuid->cust})
           [:span " "]
           [:span (datetime/month-day (:comment/created-at comment))]
           (when (and (utils/logged-in? owner)
                      (> 4 (count ancestors)))
             (list
              [:span.pre "  •  "]
              [:a {:role "button"
                   :on-click #(do
                                (if (om/get-state owner :replying?)
                                  (om/set-state! owner :replying? false)
                                  (om/set-state! owner :replying? true)))}
               (if (om/get-state owner :replying?) "Cancel" "Reply")]))]

          (when (om/get-state owner :replying?)
            [:div.content
             (om/build comment-form {:issue-id issue-id
                                     :parent-id comment-id
                                     :close-callback #(om/set-state! owner :replying? false)}
                       {:react-key "comment-form"})])
          [:div.comments {:key "child-comments"}
           (unrendered-comments-notice all-child-ids rendered-child-ids
                                       #(om/update-state! owner (fn [s]
                                                                  (assoc s :rendered-child-ids (:all-child-ids s)))))


           (when (and (not (contains? ancestors (:db/id comment))) ; don't render cycles
                      (pos? (count rendered-child-ids)))
             (for [id (reverse (sort-by (fn [i] (:comment/created-at (d/entity @issue-db i))) rendered-child-ids))]
               (om/build single-comment {:issue-id issue-id
                                         :comment-id id
                                         :uuid->cust uuid->cust}
                         {:key :comment-id
                          :opts {:ancestors (conj ancestors (:db/id comment))}})))]])))))


(defn comments [{:keys [issue-id uuid->cust]} owner]
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
            cust-uuid (:cust/uuid (om/get-shared owner :cust))]
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
                                                                                            (fn [i] (= cust-uuid (:comment/author (d/entity (:db-after tx-report) i))))
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
          (for [id (reverse (sort-by (fn [i] (:comment/created-at (d/entity @issue-db i))) rendered-top-level-ids))]
            (om/build single-comment {:comment-id id
                                      :issue-id issue-id
                                      :uuid->cust uuid->cust}
                      {:key :comment-id}))])))))

(defn issue-card [{:keys [issue-id uuid->cust]} owner]
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
         [:div.issue-card.content
          [:div.issue-info
           [:a.issue-title {:href (urls/issue-url issue)
                            :target "_top"}
            (:issue/title issue)]
           [:p.issue-foot
            [:span (str comment-count " comment" (if (not= 1 comment-count) "s"))]
            (if (= :issue.status/completed (:issue/status issue))
              ", completed"
              (when (utils/admin? owner)
                (list
                 " "
                 [:a.issue-edit-status {:on-click #(cast! :marked-issue-completed
                                                          {:issue-uuid (:frontend/issue-id issue)})}
                  "mark completed"])))]]
          (om/build vote-box {:issue issue :uuid->cust uuid->cust})])))))

(defn issue* [{:keys [issue-id uuid->cust]} owner]
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
         [:section.menu-view.issue
          [:div.issue-summary.make {:key "summary"}
           (om/build issue-card {:issue-id issue-id :uuid->cust uuid->cust})
           (om/build description-form {:issue issue :issue-id issue-id :uuid->cust uuid->cust})]

          [:div.issue-comments.make {:key "issue-comments"}
           [:div.content
            (om/build comment-form {:issue-id issue-id} {:react-key "comment-form"})]
           (om/build comments {:issue-id issue-id :uuid->cust uuid->cust} {:react-key "comments"})]])))))

(defn issue-list [{:keys [uuid->cust]} owner]
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
            cust-uuid (:cust/uuid (om/get-shared owner :cust))]
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
               (when cust-uuid
                 (om/update-state!
                  owner #(-> %
                           (assoc :all-issue-ids issue-ids)
                           (update-in [:rendered-issue-ids]
                                      (fn [r]
                                        (set/union r
                                                   (set (filter
                                                         (fn [i] (= cust-uuid
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
         [:section.menu-view.issues-list
          [:div.content.make
           (om/build issue-form {})]
          [:div.issue-cards.make {:key "issue-cards"}
           (let [deleted (set/difference rendered-issue-ids all-issue-ids)
                 added (set/difference all-issue-ids rendered-issue-ids)]
             (when (or (seq deleted) (seq added))
               [:a.issue-missing {:role "button"
                                  :on-click #(om/update-state! owner (fn [s]
                                                                       (assoc s
                                                                         :rendered-issue-ids (:all-issue-ids s)
                                                                         :render-time (datetime/server-date))))}
                [:span
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
                            " added, " (count deleted) " removed, click to refresh."))]]))
           (when-let [issues (seq (map #(d/entity @issue-db %) rendered-issue-ids))]
             (om/build-all issue-card (map (fn [i] {:issue-id (:db/id i)
                                                    :uuid->cust uuid->cust})
                                           (sort (issue-model/issue-comparator cust render-time) issues))
                           {:key :issue-id
                            :opts {:issue-db issue-db}}))]])))))

(defn issue [{:keys [issue-uuid uuid->cust]} owner]
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
          (om/build issue* {:issue-id issue-id :uuid->cust uuid->cust})
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
         (om/build issue {:issue-uuid (:active-issue-uuid app)
                          :uuid->cust (:uuid->cust app)}
                   {:key :issue-uuid})
         (om/build issue-list {:uuid->cust (:uuid->cust app)} {:react-key "issue-list"}))))))

(defn issues [app owner {:keys [submenu]}]
  (reify
    om/IDisplayName (display-name [_] "Issues Overlay")
    om/IRender
    (render [_]
      (om/build issues* (-> app
                          (select-keys [:active-issue-uuid
                                        :unsynced-datoms])
                          (assoc :uuid->cust (get-in app [:cust-data :uuid->cust])))
                {:opts {:submenu submenu}}))))
