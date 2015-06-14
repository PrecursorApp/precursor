(ns pc.http.sente
  (:require [clj-http.client :as http]
            [clj-statsd :as statsd]
            [clj-time.core :as time]
            [clojure.core.async :as async]
            [clojure.core.memoize :as memo]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.api :refer [db q] :as d]
            [pc.auth :as auth]
            [pc.billing :as billing]
            [pc.cache :as cache]
            [pc.datomic :as pcd]
            [pc.datomic.schema :as schema]
            [pc.datomic.web-peer :as web-peer]
            [pc.email :as email]
            [pc.http.datomic2 :as datomic2]
            [pc.http.datomic.common :as datomic-common]
            [pc.http.doc :as doc-http]
            [pc.http.clipboard :as clipboard]
            [pc.http.immutant-adapter :refer (sente-web-server-adapter)]
            [pc.http.issues :as issues-http]
            [pc.http.plan :as plan-http]
            [pc.http.sente.sliding-send :as sliding-send]
            [pc.http.sente.common :as common :refer (send-msg send-reply)]
            [pc.http.integrations :as integrations-http]
            [pc.http.urls :as urls]
            [pc.http.talaria :as tal]
            [pc.models.access-grant :as access-grant-model]
            [pc.models.access-request :as access-request-model]
            [pc.models.chat :as chat]
            [pc.models.clip :as clip-model]
            [pc.models.cust :as cust]
            [pc.models.doc :as doc-model]
            [pc.models.issue :as issue-model]
            [pc.models.layer :as layer-model]
            [pc.models.permission :as permission-model]
            [pc.models.plan :as plan-model]
            [pc.models.team :as team-model]
            [pc.nts :as nts]
            [pc.replay :as replay]
            [pc.rollbar :as rollbar]
            [pc.slack :as slack]
            [pc.sms :as sms]
            [pc.stripe :as stripe]
            [pc.utils :as utils]
            [slingshot.slingshot :refer (try+ throw+)]
            [taoensso.sente :as sente])
  (:import java.util.UUID))

(defonce client-stats (atom {}))

;; TODO: find a way to restart sente
(defonce sente-state (atom {}))

(defn uuid
  []
  (UUID/randomUUID))

(defn user-id-fn [req]
  ;; {:pre [(seq (get-in req [:session :sente-id]))
  ;;        (seq (get-in req [:params :tab-id]))]}
  (when (empty? (get-in req [:session :sente-id]))
    (let [msg (format "sente-id is nil for %s on %s" (:remote-addr req) (:uri req))]
      (rollbar/report-exception (Exception. msg) :request (:ring-req req) :cust (some-> req :ring-req :auth :cust))
      (log/errorf msg)))

  (when (empty? (get-in req [:params "tab-id"]))
    (let [msg (format "tab-id is nil for %s on %s" (:remote-addr req) (:uri req))]
      (rollbar/report-exception (Exception. msg) :request (:ring-req req) :cust (some-> req :ring-req :auth :cust))
      (log/errorf msg)))

  (str (get-in req [:session :sente-id])
       "-"
       (get-in req [:params "tab-id"])))

;; hash-map of document-id to connected users
;; Used to keep track of which transactions to send to which user
;; sente's channel handling stuff is not much fun to work with :(
;; e.g {:12345 {:uuid-1 {show-mouse?: true} :uuid-1 {:show-mouse? false}}}
(defonce document-subs (atom {}))
(defonce team-subs (atom {}))

(defn track-document-subs []
  (add-watch document-subs :statsd (fn [_ _ old-state new-state]
                                     (when (not= (count old-state)
                                                 (count new-state))
                                       (statsd/gauge "document-subs" (count new-state))))))

(defn track-clients []
  (add-watch client-stats :statsd (fn [_ _ old-state new-state]
                                    (when (not= (count old-state)
                                                (count new-state))
                                      (statsd/gauge "connected-clients" (count new-state))))))

(defn track-team-subs []
  (add-watch team-subs :statsd (fn [_ _ old-state new-state]
                                 (when (not= (count old-state)
                                             (count new-state))
                                   (statsd/gauge "team-subs" (count new-state))))))

(defn notify-document-transaction [db {:keys [read-only-data admin-data annotations]}]
  (let [doc (:transaction/document annotations)
        doc-id (:db/id doc)]
    (doseq [[uid {:keys [auth]}] (dissoc (get @document-subs doc-id) (:session/client-id annotations))
            :let [max-scope (auth/max-document-scope db doc auth)]]
      (log/infof "notifying %s about new transactions for %s" uid doc-id)
      (send-msg {:sente-state sente-state
                 ;; TODO: probably want to get tal-state from somewhere else
                 :tal/state tal/talaria-state}
                uid
                [:datomic/transaction (cond (auth/contains-scope? auth/scope-heirarchy max-scope :admin)
                                            admin-data
                                            (auth/contains-scope? auth/scope-heirarchy max-scope :read)
                                            read-only-data)]))
    (when-let [server-timestamps (seq (filter #(= :server/timestamp (:a %)) (:tx-data admin-data)))]
      (log/infof "notifying %s about new server timestamp for %s" (:session/uuid annotations) doc-id)
      ;; they made the tx, so we can send them the admin data
      (send-msg {:sente-state sente-state
                 :tal/state tal/talaria-state}
                (str (:session/client-id annotations))
                [:datomic/transaction {:tx-data server-timestamps}]))))

;; Note: this assumes that everyone subscribe to the team is an admin
(defn notify-team-transaction [db {:keys [read-only-data admin-data annotations]}]
  (let [team-uuid (:team/uuid (:transaction/team annotations))]
    (doseq [[uid _] (dissoc (get @team-subs team-uuid) (:session/client-id annotations))]
      (log/infof "notifying %s about new team transactions for %s" uid team-uuid)
      (send-msg {:sente-state sente-state
                 :tal/state tal/talaria-state}
                uid
                [:team/transaction admin-data]))
    (when-let [server-timestamps (seq (filter #(= :server/timestamp (:a %)) (:tx-data admin-data)))]
      (log/infof "notifying %s about new team server timestamp for %s" (:session/uuid admin-data) team-uuid)
      (send-msg {:sente-state sente-state
                 :tal/state tal/talaria-state}
                (str (:session/client-id annotations))
                [:team/transaction {:tx-data server-timestamps}]))))

(defn notify-issue-transaction [db data]
  (doseq [uid @issues-http/issue-subs]
    (log/infof "notifying %s about new issue transactions" uid)
    (send-msg {:sente-state sente-state
               :tal/state tal/talaria-state}
              uid
              [:issue/transaction data])))

(defn has-document-access? [doc-id req scope]
  (let [doc (doc-model/find-by-id (:db req) doc-id)]
    (auth/has-document-permission? (:db req) doc (-> req :ring-req :auth) scope)))

;; TODO: make sure to kick the user out of subscribed if he loses access
(defn check-subscribed [doc-id req scope]
  ;; TODO: we're making a simplifying assumption that subscribed at least
  ;;       gives you read access
  (when (= scope :read)
    (get-in @document-subs [doc-id (-> req :client-id)])))

(defn check-document-access [doc-id req scope]
  {:pre [doc-id]}
  (if (or (check-subscribed doc-id req scope)
          (has-document-access? doc-id req scope))
    true
    (if (auth/logged-in? (:ring-req req))
      (throw+ {:status 403
               :error-msg "This document is private. Please request access."
               :error-key :document/permission-error})
      (throw+ {:status 401
               :error-msg "This document is private. Please log in to access it."
               :error-key :document/permission-error}))))

(defn check-team-subscribed [team-uuid req scope]
  (when (= scope :admin)
    (get-in @team-subs [team-uuid (-> req :client-id)])))

(defn has-team-permission? [team-uuid req scope]
  (let [team (team-model/find-by-uuid (:db req) team-uuid)]
    (auth/has-team-permission? (:db req) team (-> req :ring-req :auth) scope)))

(defn check-team-access [team-uuid req scope]
  {:pre [team-uuid]}
  (if (or (check-team-subscribed team-uuid req scope)
          (has-team-permission? team-uuid req scope))
    true
    (if (auth/logged-in? (:ring-req req))
      (throw+ {:status 403
               :error-msg "This team is private. Please request access."
               :error-key :team/permission-error})
      (throw+ {:status 401
               :error-msg "This team is private. Please log in to access it."
               :error-key :team/permission-error}))))

(defn choose-frontend-id-seed [db document-id subs requested-remainder]
  (let [available-remainders (apply disj web-peer/remainders (map (comp :remainder :frontend-id-seed)
                                                                  (vals subs)))]
    (if-let [remainder (if (contains? available-remainders requested-remainder)
                         requested-remainder
                         (first available-remainders))]
      (let [used-client-parts (web-peer/client-parts-for-ns db document-id)
            used-from-partition (set (filter #(= remainder (mod % web-peer/multiple)) used-client-parts))
            start-eid (first (remove #(contains? used-from-partition %)
                                     (iterate (partial + web-peer/multiple) (+ web-peer/multiple remainder))))]
        {:remainder remainder :multiple web-peer/multiple :next-id start-eid})
      (throw+ {:status 403
               :error-msg "There are too many users in the document."
               :error-key :too-many-subscribers}))))

(defn subscribe-to-doc [db document-id uuid cust & {:keys [requested-color requested-remainder]}]
  (swap! client-stats update-in [uuid] merge {:document {:db/id document-id}})
  (swap! document-subs update-in [document-id]
         (fn [subs]
           (-> subs
             (assoc-in [uuid :client-id] uuid)
             (assoc-in [uuid :auth] {:cust cust})
             (update-in [uuid] merge (select-keys cust [:cust/uuid :cust/color-name :cust/name]))
             (assoc-in [uuid :show-mouse?] true)
             (assoc-in [uuid :frontend-id-seed] (choose-frontend-id-seed db document-id subs requested-remainder))))))

(defn clean-document-subs [client-id]
  (swap! document-subs (fn [ds]
                         ;; Could be optimized...
                         (reduce (fn [acc [document-id client-ids]]
                                   (if-not (contains? client-ids client-id)
                                     acc
                                     (let [new-client-ids (dissoc client-ids client-id)]
                                       (if (empty? new-client-ids)
                                         (dissoc acc document-id)
                                         (assoc acc document-id new-client-ids)))))
                                 ds ds))))

(defn clean-team-subs [client-id]
  (swap! team-subs (fn [ds]
                     ;; Could be optimized...
                     (reduce (fn [acc [team-uuid client-ids]]
                               (if-not (contains? client-ids client-id)
                                 acc
                                 (let [new-client-ids (dissoc client-ids client-id)]
                                   (if (empty? new-client-ids)
                                     (dissoc acc team-uuid)
                                     (assoc acc team-uuid new-client-ids)))))
                             ds ds))))

(defn close-connection [client-id]
  (log/infof "closing connection for %s" client-id)
  (doseq [uid (reduce (fn [acc [doc-id clients]]
                        (if (contains? clients client-id)
                          (set/union acc (keys clients))
                          acc))
                      #{} @document-subs)]
    (log/infof "notifying %s about %s leaving" uid client-id)
    (send-msg {:sente-state sente-state
               :tal/state tal/talaria-state}
              uid
              [:frontend/subscriber-left {:client-id client-id}]))
  (clean-team-subs client-id)
  (clean-document-subs client-id)
  (issues-http/unsubscribe client-id)
  (swap! client-stats dissoc client-id))

(defn subscribe-to-team [team-uuid uuid cust]
  (swap! client-stats update-in [uuid] merge {:team {:db/id team-uuid}})
  (swap! team-subs update-in [team-uuid]
         (fn [subs]
           (-> subs
             (assoc-in [uuid] {:client-id uuid
                               :cust/uuid (:cust/uuid cust)})))))

(defn ws-handler-dispatch-fn [req]
  (-> req :event first))

;; additional handlers defined in pc.http.issues
(defmulti ws-handler ws-handler-dispatch-fn)

(defmethod ws-handler :default [req]
  (log/infof "%s for %s" (:event req) (:client-id req)))

(defmethod ws-handler :chsk/uidport-close [{:keys [client-id] :as req}]
  (close-connection client-id))

(defmethod ws-handler :tal/channel-close [{:keys [client-id] :as req}]
  (close-connection client-id))

(defmethod ws-handler :frontend/close-connection [{:keys [client-id] :as req}]
  (close-connection client-id))

(defmethod ws-handler :frontend/unsubscribe [{:keys [client-id ?data ?reply-fn] :as req}]
  (check-document-access (-> ?data :document-id) req :read)
  (let [document-id (-> ?data :document-id)]
    (log/infof "unsubscribing %s from %s" client-id document-id)
    (close-connection client-id)
    (doseq [[uid _] (get @document-subs document-id)]
      (send-msg req uid [:frontend/subscriber-left {:client-id client-id}]))))

(defmethod ws-handler :team/subscribe [{:keys [client-id ?data ?reply-fn] :as req}]
  (let [team-uuid (-> ?data :team/uuid)
        team (team-model/find-by-uuid (:db req) team-uuid)
        send-fn (partial send-msg req)]
    (check-team-access team-uuid req :admin)

    (subscribe-to-team team-uuid client-id (get-in req [:ring-req :auth :cust]))
    (send-fn client-id [:team/db-entities
                        {:team/uuid team-uuid
                         :entities [(team-model/read-api team)]
                         :entity-type :team}])

    (log/infof "sending permission-data for team %s to %s" (:team/subdomain team) client-id)
    (send-fn client-id [:team/db-entities
                        {:team/uuid team-uuid
                         :entities (map (partial permission-model/read-api (:db req))
                                        (filter #(or (:permission/cust-ref %)
                                                     (:permission/reason %))
                                                (permission-model/find-by-team (:db req)
                                                                               team)))
                         :entity-type :permission}])
    (send-fn client-id [:team/db-entities
                        {:team/uuid team-uuid
                         :entities (map access-grant-model/read-api
                                        (access-grant-model/find-by-team (:db req)
                                                                         team))
                         :entity-type :access-grant}])

    (send-fn client-id [:team/db-entities
                        {:team/uuid team-uuid
                         :entities (map (partial access-request-model/read-api (:db req))
                                        (access-request-model/find-by-team (:db req)
                                                                           team))
                         :entity-type :access-request}])

    (send-fn client-id [:team/db-entities
                        {:team/uuid team-uuid
                         :entities [(plan-model/read-api (:team/plan team))]
                         :entity-type :plan}])

    (send-fn client-id [:team/db-entities
                        {:team/uuid team-uuid
                         :entities (map team-model/slack-hook-read-api (:team/slack-hooks team))
                         :entity-type :slack-hooks}])))

(defmethod ws-handler :issue/subscribe [req]
  (log/infof "subscribing to issues for %s" (:client-id req))
  (issues-http/subscribe req))

(defmethod ws-handler :issue/unsubscribe [req]
  (log/infof "unsubscribing from issues for %s" (:client-id req))
  (issues-http/unsubscribe (:client-id req)))

(defn subscriber-read-api [subscriber]
  (-> subscriber
    (select-keys [:client-id
                  :cust/uuid
                  :cust/name
                  :cust/color-name
                  :frontend-id-seed
                  :hide-in-list?
                  :tool
                  :layers
                  :relation
                  :recording
                  :show-mouse?
                  :chat-body])
    (merge (when (:mouse-position subscriber)
             (select-keys subscriber [:mouse-position])))))

;; TODO: subscribe should be the only function you need when you get to a doc, then it should send
;;       all of the data asynchronously
(defmethod ws-handler :frontend/subscribe [{:keys [client-id ?data ?reply-fn] :as req}]
  (try+
   (check-document-access (-> ?data :document-id) req :read)
   (catch :status t
     (send-reply req [:subscribe/error])
     (throw+)))
  (let [document-id (-> ?data :document-id)
        send-fn (partial send-msg req)
        _ (log/infof "subscribing %s to %s" client-id document-id)
        subs (subscribe-to-doc (:db req)
                               document-id
                               client-id
                               (-> req :ring-req :auth :cust)
                               :requested-color (:requested-color ?data)
                               :requested-remainder (:requested-remainder ?data))
        doc (doc-model/find-by-id (:db req) document-id)]

    (send-reply req [:frontend/frontend-id-state {:frontend-id-state (get-in subs [document-id client-id :frontend-id-seed])
                                                  :max-document-scope (auth/max-document-scope (:db req) doc (get-in req [:ring-req :auth]))}])

    (doseq [[uid _] (get @document-subs document-id)]
      (send-fn uid [:frontend/subscriber-joined (subscriber-read-api (merge {:client-id client-id}
                                                                            (get-in subs [document-id client-id])))]))

    ;; TODO: we'll need a read-api or something here at some point
    (log/infof "sending document for %s to %s" document-id client-id)
    (send-fn client-id [:frontend/db-entities
                        {:document/id document-id
                         :entities [(doc-model/read-api (doc-model/find-by-id (:db req) document-id))]
                         :entity-type :document}])

    (log/infof "sending layers for %s to %s" document-id client-id)
    (send-fn client-id [:frontend/db-entities
                        {:document/id document-id
                         :entities (map layer-model/read-api (layer-model/find-by-document (:db req) {:db/id document-id}))
                         :entity-type :layer}])

    (log/infof "sending custs for %s to %s" document-id client-id)
    (send-fn client-id [:frontend/custs
                        {:document/id document-id
                         :uuid->cust (->> document-id
                                       (cust/cust-uuids-for-doc (:db req))
                                       (cons (:cust/uuid (cust/prcrsr-bot (:db req))))
                                       set
                                       (set/union (disj (set (map :cust/uuid (vals (get subs document-id))))
                                                        nil))
                                       (cust/public-read-api-per-uuids (:db req)))}])

    (log/infof "sending chats %s to %s" document-id client-id)
    (send-fn client-id [:frontend/db-entities
                        {:document/id document-id
                         :entities (map chat/read-api (chat/find-by-document (:db req) {:db/id document-id}))
                         :entity-type :chat}])
    (log/infof "sending subscribers for %s to %s" document-id client-id)
    (send-fn client-id [:frontend/subscribers
                        {:document/id document-id
                         :subscribers (reduce (fn [acc [client-id subscriber]]
                                                (assoc acc client-id (subscriber-read-api subscriber)))
                                              {} (get subs document-id))}])

    (when-let [issue (issue-model/find-by-doc (:db req) {:db/id document-id})]
      (log/infof "sending issue for %s to %s" document-id client-id)
      (send-fn client-id [:issue/db-entities
                          {:entities [(issue-model/summary-read-api issue)]
                           :entity-type :issue}]))

    ;; These are interesting b/c they're read-only. And by "interesting", I mean "bad"
    ;; We should find a way to let the frontend edit things
    ;; TODO: only send this stuff when it's needed
    (when (has-document-access? document-id req :admin)
      (log/infof "sending permission-data for %s to %s" document-id client-id)
      (send-fn client-id [:frontend/db-entities
                          {:document/id document-id
                           :entities (map (partial permission-model/read-api (:db req))
                                          (filter :permission/cust-ref
                                                  (permission-model/find-by-document (:db req)
                                                                                     {:db/id document-id})))
                           :entity-type :permission}])

      (send-fn client-id [:frontend/db-entities
                          {:document/id document-id
                           :entities (map access-grant-model/read-api
                                          (access-grant-model/find-by-document (:db req)
                                                                               {:db/id document-id}))
                           :entity-type :access-grant}])

      (send-fn client-id [:frontend/db-entities
                          {:document/id document-id
                           :entities (map (partial access-request-model/read-api (:db req))
                                          (access-request-model/find-by-document (:db req)
                                                                                 {:db/id document-id}))
                           :entity-type :access-request}]))))

(defmethod ws-handler :cust/fetch-teams [{:keys [client-id ?data ?reply-fn] :as req}]
  (when-let [cust (-> req :ring-req :auth :cust)]
    (send-reply req {:teams (set (map (comp team-model/read-api :permission/team) (permission-model/find-team-permissions-for-cust (:db req) cust)))})))

(defmethod ws-handler :cust/fetch-clipboard-s3-url [{:keys [client-id ?data ?reply-fn] :as req}]
  (when-let [cust (-> req :ring-req :auth :cust)]
    (log/infof "sending presigned url for %s" (:cust/email cust))
    (send-reply req (clipboard/create-presigned-clipboard-url cust))))

(defmethod ws-handler :cust/store-clipboard [{:keys [client-id ?data ?reply-fn] :as req}]
  (when-let [cust (-> req :ring-req :auth :cust)]
    (let [bucket (pc.profile/clipboard-bucket)
          key (:key ?data)]
      (log/infof "storing clipboard data for %s" (:cust/email cust))
      @(d/transact (pcd/conn) [{:db/id (:db/id cust)
                                :cust/clips {:db/id (d/tempid :db.part/user)
                                             :clip/s3-bucket bucket
                                             :clip/s3-key key
                                             :clip/uuid (d/squuid)}}]))))

(defmethod ws-handler :cust/fetch-clips [{:keys [client-id ?data ?reply-fn] :as req}]
  (when-let [cust (-> req :ring-req :auth :cust)]
    (let [clips (clip-model/find-by-cust (:db req) cust)]
      (log/infof "sending %s clips to %s" (count clips) (:cust/email cust))
      (send-reply req {:clips (map (fn [c] (-> c
                                             (select-keys [:clip/uuid :clip/important?])
                                             (assoc :clip/s3-url (clipboard/create-presigned-clip-url c))))
                                   clips)}))))

(defmethod ws-handler :cust/delete-clip [{:keys [client-id ?data ?reply-fn] :as req}]
  (when-let [cust (-> req :ring-req :auth :cust)]
    (when-let [clip (clip-model/find-by-cust-and-uuid (:db req) cust (:clip/uuid ?data))]
      (log/infof "deleting clip %s for %s" (:db/id clip) (:cust/email cust))
      @(d/transact (pcd/conn) [[:db.fn/retractEntity (:db/id clip)]])
      (send-reply req {:deleted? true
                       :clip/uuid (:clip/uuid clip)}))))

(defmethod ws-handler :cust/tag-clip [{:keys [client-id ?data ?reply-fn] :as req}]
  (when-let [cust (-> req :ring-req :auth :cust)]
    (when-let [clip (clip-model/find-by-cust-and-uuid (:db req) cust (:clip/uuid ?data))]
      (log/infof "marking clip %s important for %s" (:db/id clip) (:cust/email cust))
      @(d/transact (pcd/conn) [[:db/add (:db/id clip) :clip/important? true]])
      (send-reply req {:tagged? true
                       :clip/uuid (:clip/uuid clip)}))))

(defmethod ws-handler :cust/untag-clip [{:keys [client-id ?data ?reply-fn] :as req}]
  (when-let [cust (-> req :ring-req :auth :cust)]
    (when-let [clip (clip-model/find-by-cust-and-uuid (:db req) cust (:clip/uuid ?data))]
      (log/infof "marking clip %s unimportant for %s" (:db/id clip) (:cust/email cust))
      @(d/transact (pcd/conn) [[:db/retract (:db/id clip) :clip/important? true]])
      (send-reply req {:untagged? true
                       :clip/uuid (:clip/uuid clip)}))))

(defmethod ws-handler :frontend/fetch-custs [{:keys [client-id ?data ?reply-fn] :as req}]
  (let [uuids (->> ?data :uuids)]
    (assert (>= 100 (count uuids)) "Can only fetch 100 uuids at once")
    (send-msg req client-id [:frontend/custs {:uuid->cust (cust/public-read-api-per-uuids (:db req) uuids)}])))

(defmethod ws-handler :frontend/fetch-touched [{:keys [client-id ?data ?reply-fn] :as req}]
  (when-let [cust (-> req :ring-req :auth :cust)]
    (let [ ;; TODO: at some point we may want to limit, but it's just a
          ;; list of longs, so meh
          ;; limit (get ?data :limit 100)
          ;; offset (get ?data :offset 0)
          doc-ids (if-let [team (get-in req [:ring-req :team])]
                    (doc-model/find-touched-by-cust-in-team (:db req) cust team)
                    (doc-model/find-touched-by-cust (:db req) cust))]
      (log/infof "fetched %s touched for %s" (count doc-ids) client-id)
      (send-reply req {:docs (map (fn [doc-id] {:db/id doc-id
                                                :document/name (:document/name (d/entity (:db req) doc-id))
                                                :last-updated-instant (doc-model/last-updated-time (:db req) doc-id)})
                                  doc-ids)}))))

(defmethod ws-handler :team/fetch-touched [{:keys [client-id ?data ?reply-fn] :as req}]
  (let [team-uuid (-> ?data :team/uuid)
        team (team-model/find-by-uuid (:db req) team-uuid)]
    (check-team-access team-uuid req :admin)
    (let [ ;; TODO: at some point we may want to limit, but it's just a
          ;; list of longs, so meh
          ;; limit (get ?data :limit 100)
          ;; offset (get ?data :offset 0)
          doc-ids (team-model/find-doc-ids (:db req) team)]
      (log/infof "fetched %s touched in %s for %s" (count doc-ids) (:team/subdomain team) client-id)
      (send-reply req {:docs (map (fn [doc-id] {:db/id doc-id
                                                :document/name (:document/name (d/entity (:db req) doc-id))
                                                :last-updated-instant (doc-model/last-updated-time (:db req) doc-id)})
                                  doc-ids)}))))

(defmethod ws-handler :team/plan-active-history [{:keys [client-id ?data ?reply-fn] :as req}]
  (let [team-uuid (-> ?data :team/uuid)
        team (team-model/find-by-uuid (:db req) team-uuid)]
    (check-team-access team-uuid req :admin)
    (let [history (->> (billing/active-history (:db req) team)
                    (map (fn [info]
                           (update-in info [:cust] :cust/email))))]
      (log/infof "fetched %s entries in active history for %s" (count history) (:team/subdomain team))
      (send-reply req {:history history
                       :team/uuid team-uuid}))))

(defn determine-type [datom datoms]
  (let [e (:e datom)
        attr-nses (map (comp namespace :a) (filter #(= (:e %) (:e datom)) datoms))]
    (cond (first (filter #(= "layer" %) attr-nses))
          :layer/document

          (first (filter #(= "chat" %) attr-nses))
          :chat/document

          :else (throw+ {:error :invalid-type-fordocument-id
                         :datom datom
                         :datoms datoms}))))

(defmethod ws-handler :frontend/transaction [{:keys [client-id ?data ?reply-fn] :as req}]
  (let [document-id (-> ?data :document/id)
        access-scope (if (has-document-access? document-id req :admin)
                       :admin
                       ;; will throw, so we can't get any further
                       (when (check-document-access document-id req :read)
                         :read))
        _ (assert (keyword? access-scope)) ;; just in case
        datoms (->> ?data
                 :datoms
                 (remove (comp nil? :v))
                 ;; Don't let people sneak layers into other documents
                 (reduce (fn [acc d]
                           (if (= "document" (name (:a d)))
                             (conj acc
                                   (assoc d :v document-id))
                             (conj acc d)))
                         []))
        cust-uuid (-> req :ring-req :auth :cust :cust/uuid)
        ;; note that these aren't all of the rejected datoms, just the ones not on the whitelist
        rejects (remove (comp (partial datomic2/whitelisted? access-scope)
                              pcd/datom->transaction)
                        datoms)]
    (log/infof "transacting %s datoms (minus %s rejects) on %s for %s" (count datoms) (count rejects) document-id client-id)
    (datomic2/transact! datoms
                        {:document-id document-id
                         :access-scope access-scope
                         :client-id client-id
                         :cust-uuid cust-uuid
                         :frontend-id-seed (get-in @document-subs [document-id client-id :frontend-id-seed])
                         :session-uuid (UUID/fromString (get-in req [:ring-req :session :sente-id]))
                         :timestamp (:receive-instant req)})
    (send-reply req {:rejected-datoms rejects})))

(defmethod ws-handler :team/transaction [{:keys [client-id ?data ?reply-fn] :as req}]
  (let [team-uuid (-> ?data :team/uuid)]
    (check-team-access team-uuid req :admin)
    (let [datoms (->> ?data
                   :datoms
                   (remove (comp nil? :v))
                   (remove #(= "document" (name (:a %)))))
          cust-uuid (-> req :ring-req :auth :cust :cust/uuid)
          ;; note that these aren't all of the rejected datoms, just the ones not on the whitelist
          rejects (remove (comp (partial datomic2/whitelisted? :admin)
                                pcd/datom->transaction)
                          datoms)
          team-id (:db/id (team-model/find-by-uuid (:db req) team-uuid))]
      (log/infof "transacting %s datoms (minus %s rejects) on %s for %s" (count datoms) (count rejects) team-id client-id)
      (datomic2/transact! datoms
                          {:team-id team-id
                           :access-scope :admin
                           :client-id client-id
                           :cust-uuid cust-uuid
                           :session-uuid (UUID/fromString (get-in req [:ring-req :session :sente-id]))
                           :timestamp (:receive-instant req)})
      (send-reply req {:rejected-datoms rejects}))))

(defmethod ws-handler :issue/transaction [{:keys [client-id ?data ?reply-fn] :as req}]
  (issues-http/handle-transaction req))

(defmethod ws-handler :issue/set-status [{:keys [client-id ?data ?reply-fn] :as req}]
  (issues-http/set-status req))

(defmethod ws-handler :frontend/mouse-position [{:keys [client-id ?data] :as req}]
  (check-document-access (-> ?data :document/id) req :read)
  (let [document-id (-> ?data :document/id)
        mouse-position (-> ?data :mouse-position)
        tool (-> ?data :tool)
        layers (-> ?data :layers)
        relation (-> ?data :relation)
        recording (-> ?data :recording)
        chat-body (-> ?data :chat-body)
        message [:frontend/mouse-move (subscriber-read-api {:client-id client-id
                                                            :tool tool
                                                            :layers layers
                                                            :relation relation
                                                            :recording recording
                                                            :mouse-position mouse-position
                                                            :chat-body chat-body})]]
    (swap! document-subs utils/update-when-in [document-id client-id] merge {:mouse-position mouse-position
                                                                             :tool tool
                                                                             :recording recording})
    (doseq [[uid _] (dissoc (get @document-subs document-id) client-id)]
      (sliding-send/sliding-send req uid message))))

(defmethod ws-handler :rtc/signal [{:keys [client-id ?data] :as req}]
  (check-document-access (-> ?data :document/id) req :read)
  (let [document-id (:document/id ?data)
        consumer (-> ?data :consumer)
        producer (-> ?data :producer)
        target (first (filter #(not= client-id %) [consumer producer]))
        data (select-keys ?data [:candidate :sdp :consumer :producer :subscribe-to-recording :stream-id :close-connection :connection-failed])]
    (assert (contains? (set [consumer producer]) client-id)
            (format "client (%s) is not the consumer (%s) or producer (%s)" client-id consumer producer))
    (if (get-in @document-subs [document-id target])
      (do
        (log/infof "sending signal from %s to %s" client-id target)
        (send-msg req target [:rtc/signal (assoc data
                                                            :ice-servers (nts/get-ice-servers))]))
      (log/warnf (format "%s is the target, but isn't subscribed to %s" target document-id)))))

(defmethod ws-handler :rtc/diagnostics [{:keys [client-id ?data] :as req}]
  (email/send-connection-stats {:client-id client-id
                                :cust (select-keys (get-in req [:ring-req :auth :cust])
                                                   [:cust/email :cust/name])
                                :data ?data}))

(defmethod ws-handler :frontend/update-self [{:keys [client-id ?data] :as req}]
  ;; TODO: update subscribers in a different way
  (check-document-access (-> ?data :document/id) req :read)
  (when-let [cust (-> req :ring-req :auth :cust)]
    (let [doc-id (-> ?data :document/id)]
      (log/infof "updating self for %s" (:cust/uuid cust))
      (when (:cust/color-name ?data)
        (assert (contains? (schema/color-enums) (:cust/color-name ?data))))
      (let [new-cust (cust/update! cust (select-keys ?data [:cust/name :cust/color-name]))
            collab-uuids (reduce (fn [acc subs]
                                   (if (first (filter #(= (:cust/uuid (second %)) (:cust/uuid new-cust))
                                                      subs))
                                     (concat acc (keys subs))
                                     acc))
                                 () (vals @document-subs))
            msg [:frontend/custs {:uuid->cust {(:cust/uuid new-cust) (cust/public-read-api new-cust)}}]]
        (doseq [uid collab-uuids]
          (send-msg req uid msg))
        (when (contains? @issues-http/issue-subs client-id)
          (doseq [uid (set/difference @issues-http/issue-subs (set collab-uuids))]
            (send-msg req uid msg)))))))

(defmethod ws-handler :frontend/send-invite [{:keys [client-id ?data ?reply-fn] :as req}]
  ;; This may turn out to be a bad idea, but error handling is done through creating chats
  (check-document-access (-> ?data :document/id) req :admin)
  (let [doc-id (-> ?data :document/id)
        doc (doc-model/find-by-id (:db req) doc-id)
        invite-loc (-> ?data :invite-loc)
        chat-id (d/tempid :db.part/user)
        cust (-> req :ring-req :auth :cust)
        notify-invite (fn [body]
                        (if (= :overlay invite-loc)
                          (send-msg req client-id
                           [:frontend/invite-response {:document/id doc-id
                                                       :response body}])
                          @(d/transact (pcd/conn) [(merge {:db/id (d/tempid :db.part/tx)
                                                           :transaction/document doc-id
                                                           :transaction/broadcast true}
                                                          (when cust
                                                            {:cust/uuid (:cust/uuid cust)}))
                                                   (web-peer/server-frontend-id chat-id doc-id)
                                                   {:chat/body body
                                                    :server/timestamp (java.util.Date.)
                                                    :client/timestamp (java.util.Date.)
                                                    :chat/document doc-id
                                                    :db/id chat-id
                                                    :cust/uuid (:cust/uuid (cust/prcrsr-bot (:db req)))}])))]
    (if-let [cust (-> req :ring-req :auth :cust)]
      (let [email (-> ?data :email)]
        (log/infof "%s sending an email to %s on doc %s" (:cust/email cust) email doc-id)
        (try
          (email/send-chat-invite {:cust cust :to-email email :doc doc :db (:db req)})
          (notify-invite (str "Invite sent to " email))
          (catch Exception e
            (rollbar/report-exception e :request (:ring-req req) :cust (some-> req :ring-req :auth :cust))
            (log/error e)
            (.printStackTrace e)
            (notify-invite (str "Sorry! There was a problem sending the invite to " email)))))

      (notify-invite "Please sign up to send an invite."))))

(defmethod ws-handler :frontend/sms-invite [{:keys [client-id ?data ?reply-fn] :as req}]
  ;; This may turn out to be a bad idea, but error handling is done through creating chats
  (check-document-access (-> ?data :document/id) req :admin)
  (def myreq req)
  (let [doc-id (-> ?data :document/id)
        doc (doc-model/find-by-id (:db req) doc-id)
        invite-loc (-> ?data :invite-loc)
        chat-id (d/tempid :db.part/user)
        cust (-> req :ring-req :auth :cust)
        notify-invite (fn [body]
                        (if (= :overlay invite-loc)
                          (send-msg req client-id
                                    [:frontend/invite-response {:document/id doc-id
                                                                :response body}])))]
    (if-let [cust (-> req :ring-req :auth :cust)]
      (let [phone-number (-> ?data :phone-number)
            stripped-phone-number (str/replace phone-number #"[^0-9]" "")]
        (log/infof "%s sending an sms to %s on doc %s" (:cust/email cust) phone-number doc-id)
        (try
          (sms/async-send-sms stripped-phone-number
                              (format "Make something with me on Precursor. %s" (urls/from-doc doc))
                              :image-url (-> (doc-http/save-png-to-s3 (:db req) doc)
                                           :key
                                           (doc-http/generate-s3-doc-png-url))
                              :callback (fn [resp]
                                          (if (http/unexceptional-status? (:status resp))
                                            (notify-invite (str "Sent text to " phone-number))
                                            (do (notify-invite (str "Sorry! There was a problem sending the text to " phone-number))
                                                (rollbar/report-error "Error sending sms" {:http-resp resp
                                                                                           :request (:ring-req req)
                                                                                           :cust (some-> req :ring-req :auth :cust)})
                                                (log/errorf "Error sending sms: %s" resp)))))
          (catch Exception e
            (rollbar/report-exception e :request (:ring-req req) :cust (some-> req :ring-req :auth :cust))
            (log/error e)
            (.printStackTrace e)
            (notify-invite (str "Sorry! There was a problem sending the text to " phone-number)))))

      (notify-invite "Please sign up to send a text."))))

(defmethod ws-handler :frontend/change-privacy [{:keys [client-id ?data ?reply-fn] :as req}]
  (check-document-access (-> ?data :document/id) req :owner)
  (let [doc-id (-> ?data :document/id)
        cust (-> req :ring-req :auth :cust)
        setting (-> ?data :setting)
        ;; XXX: only if they try to change it to private
        _ (assert (or (:document/team (doc-model/find-by-id (:db req) doc-id))
                      (contains? #{:document.privacy/public :document.privacy/read-only} setting)
                      (contains? (:flags cust) :flags/private-docs)))
        ;; letting datomic's schema do validation for us, might be a bad idea?
        annotations {:transaction/document doc-id
                     :cust/uuid (:cust/uuid cust)
                     :transaction/broadcast true}
        txid (d/tempid :db.part/tx)]
    (d/transact (pcd/conn) [(assoc annotations :db/id txid)
                            [:db/add doc-id :document/privacy setting]])))

(defmethod ws-handler :frontend/create-image-permission [{:keys [client-id ?data ?reply-fn] :as req}]
  (check-document-access (-> ?data :document/id) req :admin)
  (let [doc-id (-> ?data :document/id)]
    (if-let [cust (-> req :ring-req :auth :cust)]
      (let [reason (:reason ?data)
            annotations {:cust/uuid (:cust/uuid cust)
                         :transaction/document doc-id
                         :transaction/broadcast true}
            _ (log/infof "Creating image permission for %s" doc-id)
            permission (or (first (permission-model/find-by-document-and-reason (:db req) {:db/id doc-id} reason))
                           (permission-model/create-document-image-permission! {:db/id doc-id}
                                                                               reason
                                                                               annotations))]
        (send-reply req {:status :success
                         :permission (permission-model/read-api (:db req) permission)}))
      (send-reply req {:status :error
                       :error-msg "Please log in"
                       :error-key :not-logged-in}))))

(defmethod ws-handler :frontend/send-permission-grant [{:keys [client-id ?data ?reply-fn] :as req}]
  (check-document-access (-> ?data :document/id) req :admin)
  (let [doc-id (-> ?data :document/id)]
    (if-let [cust (-> req :ring-req :auth :cust)]
      (let [email (-> ?data :email)
            annotations {:cust/uuid (:cust/uuid cust)
                         :transaction/document doc-id
                         :transaction/broadcast true}]
        (if-let [grantee (cust/find-by-email (:db req) email)]
          (permission-model/grant-permit {:db/id doc-id}
                                         cust
                                         grantee
                                         :permission.permits/admin
                                         annotations)
          (access-grant-model/grant-access {:db/id doc-id}
                                           email
                                           cust
                                           annotations)))
      (comment (notify-invite "Please sign up to send an invite.")))))

(defmethod ws-handler :team/send-permission-grant [{:keys [client-id ?data ?reply-fn] :as req}]
  (let [team-uuid (-> ?data :team/uuid)]
    (check-team-access team-uuid req :admin)
    (if-let [cust (-> req :ring-req :auth :cust)]
      (let [email (-> ?data :email)
            team (team-model/find-by-uuid (:db req) team-uuid)
            annotations {:cust/uuid (:cust/uuid cust)
                         :transaction/team (:db/id team)
                         :transaction/broadcast true}]
        (if-let [grantee (cust/find-by-email (:db req) email)]
          (permission-model/grant-team-permit team
                                              cust
                                              grantee
                                              :permission.permits/admin
                                              annotations)
          (access-grant-model/grant-team-access team
                                                email
                                                cust
                                                annotations)))
      (comment (notify-invite "Please sign up to send an invite.")))))

(defmethod ws-handler :frontend/grant-access-request [{:keys [client-id ?data ?reply-fn] :as req}]
  (check-document-access (-> ?data :document/id) req :admin)
  (let [doc-id (-> ?data :document/id)]
    (if-let [request (some->> ?data :request-id (access-request-model/find-by-client-part (:db req) doc-id))]
      (let [cust (-> req :ring-req :auth :cust)
            annotations {:transaction/document doc-id
                         :cust/uuid (:cust/uuid cust)
                         :transaction/broadcast true}]
        ;; TODO: need better permissions checking here. Maybe IAM-type roles for each entity?
        ;;       Right now it's too easy to accidentally forget to check.
        (assert (and doc-id (= doc-id (:db/id (:access-request/document-ref request)))))
        (permission-model/convert-access-request request cust annotations))
      (comment (notify-invite "Please sign up to send an invite.")))))

(defmethod ws-handler :team/grant-access-request [{:keys [client-id ?data ?reply-fn] :as req}]
  (let [team-uuid (-> ?data :team/uuid)]
    (check-team-access team-uuid req :admin)
    (let [team (team-model/find-by-uuid (:db req) team-uuid)]
      (if-let [request (some->> ?data :request-id (access-request-model/find-by-client-part (:db req) (:db/id team)))]
        (let [cust (-> req :ring-req :auth :cust)
              annotations {:transaction/team (:db/id team)
                           :cust/uuid (:cust/uuid cust)
                           :transaction/broadcast true}]
          ;; TODO: need better permissions checking here. Maybe IAM-type roles for each entity?
          ;;       Right now it's too easy to accidentally forget to check.
          (assert (and (:db/id team)
                       (= (:db/id team) (:db/id (:access-request/team request)))))
          (permission-model/convert-access-request request cust annotations))
        (comment (notify-invite "Please sign up to send an invite."))))))

(defmethod ws-handler :frontend/deny-access-request [{:keys [client-id ?data ?reply-fn] :as req}]
  (check-document-access (-> ?data :document/id) req :admin)
  (let [doc-id (-> ?data :document/id)]
    (if-let [request (some->> ?data :request-id (access-request-model/find-by-client-part (:db req) doc-id))]
      (let [cust (-> req :ring-req :auth :cust)
            annotations {:transaction/document doc-id
                         :cust/uuid (:cust/uuid cust)
                         :transaction/broadcast true}]
        ;; TODO: need better permissions checking here. Maybe IAM-type roles for each entity?
        ;;       Right now it's too easy to accidentally forget to check.
        (assert (and doc-id (= doc-id (:db/id (:access-request/document-ref request)))))
        (access-request-model/deny-request request annotations))
      (comment (notify-invite "Please sign up to send an invite.")))))

(defmethod ws-handler :team/create-slack-integration [{:keys [client-id ?data ?reply-fn] :as req}]
  (let [team-uuid (-> ?data :team/uuid)]
    (check-team-access team-uuid req :admin)
    (let [team (team-model/find-by-uuid (:db req) team-uuid)
          cust (-> req :ring-req :auth :cust)
          channel-name (:slack-hook/channel-name ?data)
          webhook-url (:slack-hook/webhook-url ?data)]
      (cond (empty? channel-name)
            (send-reply req {:status :error
                             :error-msg "Channel name is required"
                             :error-key :invalid-channel-name})
            (or (not (string? webhook-url))
                (not (utils/valid-url? webhook-url)))
            (send-reply req {:status :error
                             :error-msg "Webhook url is not valid"
                             :error-key :invalid-webhook-url})
            :else
            (let [slack-hook-ent (integrations-http/create-slack-hook team cust channel-name webhook-url)]

              (send-reply req {:status :success
                               :entities [(team-model/slack-hook-read-api slack-hook-ent)]}))))))

(defmethod ws-handler :team/delete-slack-integration [{:keys [client-id ?data ?reply-fn] :as req}]
  (let [team-uuid (-> ?data :team/uuid)]
    (check-team-access team-uuid req :admin)
    (let [team (team-model/find-by-uuid (:db req) team-uuid)
          cust (-> req :ring-req :auth :cust)
          hook-id (:slack-hook-id ?data)
          slack-hook (team-model/find-slack-hook-by-frontend-id (:db req) (UUID. (:db/id team) hook-id))]
      (cond (not slack-hook)
            (send-reply req {:status :error
                             :error-msg "Can't find hook"
                             :error-key :invalid-slack-hook})
            :else
            (do (integrations-http/delete-slack-hook team cust slack-hook)
                (send-reply req {:status :success}))))))

(defmethod ws-handler :team/post-doc-to-slack [{:keys [client-id ?data ?reply-fn] :as req}]
  (let [team-uuid (-> ?data :team/uuid)]
    (check-team-access team-uuid req :admin)
    (let [team (team-model/find-by-uuid (:db req) team-uuid)
          hook-id (:slack-hook-id ?data)
          slack-hook (team-model/find-slack-hook-by-frontend-id (:db req) (UUID. (:db/id team) hook-id))
          doc (doc-model/find-by-id (:db req) (:doc-id ?data))]
      (cond (not= (:db/id team) (:db/id (:document/team doc)))
            (send-reply req {:status :error
                             :error-msg "Invalid document"
                             :error-key :invalid-document})

            (not slack-hook)
            (send-reply req {:status :error
                             :error-msg "Can't find hook"
                             :error-key :invalid-slack-hook})

            :else (do
                    (log/infof "Sending slack hook for %s on %s team" (:db/id doc) (:team/subdomain team))
                    (integrations-http/send-slack-webhook slack-hook doc :message (:message ?data))
                    (send-reply req {:status :success}))))))

(defmethod ws-handler :document/transaction-ids [{:keys [client-id ?data ?reply-fn] :as req}]
  (check-document-access (-> ?data :document/id) req :read)
  (let [doc (->> ?data :document/id (doc-model/find-by-id (:db req)))]
    (let [tx-ids (replay/get-document-tx-ids (:db req) doc)]
      (log/infof "sending %s tx-ids for %s to %s" (count tx-ids) (:document/id ?data) client-id)
      (send-reply req {:tx-ids tx-ids}))))

(def ^:dynamic *db* nil)
(defonce circuit-breaker (atom nil))
(defn memo-frontend-tx-data* [tx-id]
  (-> (replay/reproduce-transaction *db* tx-id)
    (datomic-common/frontend-document-transaction)
    :read-only-data
    ;; remove entity-maps b/c easier to cache that way
    (update-in [:tx-data] vec)
    (dissoc :transaction/document)))

(defonce memo-frontend-tx-data (memo/lru memo-frontend-tx-data*
                                         :lru/threshold 10000))

(defn frontend-tx-data [db tx-id]
  (binding [*db* db]
    (memo-frontend-tx-data tx-id)))

(defn get-frontend-tx-data [db tx-id]
  (cache/wrap-memcache
   (str "pc.http.sente/frontend-tx-data-" tx-id)
   frontend-tx-data db tx-id))

(defn get-frontend-tx-data-from-cache [tx-id]
  (or (get (memo/snapshot memo-frontend-tx-data) [tx-id])
      (cache/safe-get (str "pc.http.sente/frontend-tx-data-" tx-id))))

(defmethod ws-handler :document/fetch-transaction [{:keys [client-id ?data ?reply-fn] :as req}]
  (check-document-access (-> ?data :document/id) req :read)
  (let [doc (->> ?data :document/id (doc-model/find-by-id (:db req)))
        tx-id (:tx-id ?data)]
    (if (= (:db/id doc) (:db/id (:transaction/document (d/entity (:db req) tx-id))))
      (if @circuit-breaker
        (let [transaction (get-frontend-tx-data-from-cache tx-id)]
          (if transaction
            (do (log/infof "sending %s txes from %s for %s to %s" (count (:tx-data transaction)) tx-id (:document/id ?data) client-id)
                (send-reply req {:document/transaction transaction}))
            (do (log/infof "sending :chsk/timeout b/c circuit breaker was pulled")
                (send-reply req :chsk/timeout))))
        (let [transaction (get-frontend-tx-data (:db req) tx-id)]
          (log/infof "sending %s txes from %s for %s to %s" (count (:tx-data transaction)) tx-id (:document/id ?data) client-id)
          (send-reply req {:document/transaction transaction})))
      (throw+ {:status 403
               :error-msg "The document for the transaction doesn't match the requested document."
               :error-key :invalid-transaction-id}))))

(defmethod ws-handler :team/deny-access-request [{:keys [client-id ?data ?reply-fn] :as req}]
  (let [team-uuid (-> ?data :team/uuid)]
    (check-team-access team-uuid req :admin)
    (let [team (team-model/find-by-uuid (:db req) team-uuid)]
      (if-let [request (some->> ?data :request-id (access-request-model/find-by-client-part (:db req) (:db/id team)))]
        (let [cust (-> req :ring-req :auth :cust)
              annotations {:transaction/team (:db/id team)
                           :cust/uuid (:cust/uuid cust)
                           :transaction/broadcast true}]
          ;; TODO: need better permissions checking here. Maybe IAM-type roles for each entity?
          ;;       Right now it's too easy to accidentally forget to check.
          (assert (and (:db/id team)
                       (= (:db/id team) (:db/id (:access-request/team request)))))
          (access-request-model/deny-request request annotations))
        (comment (notify-invite "Please sign up to send an invite."))))))

;; TODO: don't send request if they already have access
(defmethod ws-handler :frontend/send-permission-request [{:keys [client-id ?data ?reply-fn] :as req}]
  (let [doc-id (-> ?data :document/id)]
    (if-let [cust (-> req :ring-req :auth :cust)]
      (if-let [doc (doc-model/find-by-id (:db req) doc-id)]
        (let [email (-> ?data :email)
              annotations {:transaction/document doc-id
                           :cust/uuid (:cust/uuid cust)
                           :transaction/broadcast true}]
          (let [{:keys [db-after]} (access-request-model/create-request doc cust annotations)]
            ;; have to send it manually to the requestor b/c user won't be subscribed
            (send-msg req client-id [:frontend/db-entities
                                             {:document/id doc-id
                                              :entities (map (partial access-request-model/requester-read-api db-after)
                                                             (access-request-model/find-by-doc-and-cust db-after doc cust))
                                              :entity-type :access-request}]))))
      (comment (notify-invite "Please sign up to send an invite.")))))

(defmethod ws-handler :team/send-permission-request [{:keys [client-id ?data ?reply-fn] :as req}]
  (let [team-uuid (-> ?data :team/uuid)]
    (let [team (team-model/find-by-uuid (:db req) team-uuid)]
      (if-let [cust (-> req :ring-req :auth :cust)]
        (let [email (-> ?data :email)
              annotations {:transaction/team (:db/id team)
                           :cust/uuid (:cust/uuid cust)
                           :transaction/broadcast true}]
          (let [{:keys [db-after]} (access-request-model/create-team-request team cust annotations)]
            ;; have to send it manually to the requestor b/c user won't be subscribed
            (send-msg req client-id [:team/db-entities
                                             {:team/uuid (:team/uuid team)
                                              :entities (map (partial access-request-model/requester-read-api db-after)
                                                             (access-request-model/find-by-team-and-cust db-after team cust))
                                              :entity-type :access-request}])))
        (comment (notify-invite "Please sign up to send an invite."))))))

(defmethod ws-handler :team/create-plan [{:keys [client-id ?data ?reply-fn] :as req}]
  (let [team-uuid (-> ?data :team/uuid)]
    (check-team-access team-uuid req :admin)
    (let [team (team-model/find-by-uuid (:db req) team-uuid)
          plan (:team/plan team)
          cust (get-in req [:ring-req :auth :cust])
          token-id (:token-id ?data)
          stripe-customer (plan-http/create-stripe-customer team cust token-id)
          tx @(d/transact (pcd/conn) (concat [{:db/id (d/tempid :db.part/tx)
                                               :transaction/team (:db/id team)
                                               :cust/uuid (:cust/uuid cust)
                                               :transaction/broadcast true}
                                              (-> (plan-http/stripe-customer->plan-fields stripe-customer)
                                                (assoc :db/id (:db/id plan)
                                                       :plan/paid? true
                                                       :plan/billing-email (:cust/email cust)))]))]
      (send-reply req {:plan-created? true}))))

(defmethod ws-handler :team/update-card [{:keys [client-id ?data ?reply-fn] :as req}]
  (let [team-uuid (-> ?data :team/uuid)]
    (check-team-access team-uuid req :admin)
    (let [team (team-model/find-by-uuid (:db req) team-uuid)
          plan (:team/plan team)
          cust (get-in req [:ring-req :auth :cust])
          token-id (:token-id ?data)
          stripe-customer (plan-http/update-card team token-id)
          tx @(d/transact (pcd/conn) [{:db/id (d/tempid :db.part/tx)
                                       :transaction/team (:db/id team)
                                       :cust/uuid (:cust/uuid cust)
                                       :transaction/broadcast true}
                                      (-> (plan-http/stripe-customer->plan-fields stripe-customer)
                                        (assoc :db/id (:db/id plan)
                                               :plan/paid? true))])]
      (send-reply req {:card-updated? true}))))

(defmethod ws-handler :team/extend-trial [{:keys [client-id ?data ?reply-fn] :as req}]
  (let [team-uuid (-> ?data :team/uuid)]
    (check-team-access team-uuid req :admin)
    (let [team (team-model/find-by-uuid (:db req) team-uuid)
          plan (:team/plan team)
          cust (get-in req [:ring-req :auth :cust])
          tx (plan-model/extend-trial plan 7 :annotations {:transaction/team (:db/id team)
                                                           :transaction/broadcast true
                                                           :cust/uuid (:cust/uuid cust)})]
      (?reply-fn {:plan (plan-model/read-api (d/entity (:db-after tx) (:db/id plan)))}))))

(defmethod ws-handler :frontend/save-browser-settings [{:keys [client-id ?data ?reply-fn] :as req}]
  (if-let [cust (-> req :ring-req :auth :cust)]
    (let [settings (-> ?data :settings)]
      (log/infof "saving browser settings for %s" (:cust/email cust))
      @(d/transact (pcd/conn) [(merge {:db/id (:db/id cust)}
                                      (select-keys settings (schema/browser-setting-idents)))]))
    (let [msg (format "no cust for %s" (:remote-addr (:ring-req req)))]
      (rollbar/report-exception (Exception. msg) :request (:ring-req req) :cust (some-> req :ring-req :auth :cust))
      (log/errorf msg))))

(defmethod ws-handler :server/timestamp [{:keys [client-id ?data ?reply-fn] :as req}]
  (send-reply req [:server/timestamp {:date (java.util.Date.)}]))

(defn conj-limit
  "Expects a vector, keeps the newest n items. May return a shorter vector than was passed in."
  ([v n x]
   (subvec (conj v x) (if (> (dec n) (count v))
                        0
                        (- (count v) (dec n)))))
  ([v n x & xs]
   (if xs
     (recur (conj-limit v n x) n (first xs) (next xs))
     (conj-limit v n x))))

(defmethod ws-handler :frontend/stats [{:keys [client-id ?data ?reply-fn] :as req}]
  (swap! client-stats utils/update-when-in [client-id] merge {:stats (:stats ?data)
                                                              :last-update (time/now)
                                                              :cust (get-in req [:ring-req :auth :cust])}))

(defmethod ws-handler :chsk/ws-ping [req]
  ;; don't log
  nil)

(defn handle-req [req]
  (utils/with-report-exceptions
    (let [client-id (user-id-fn (:ring-req req))
          event (ws-handler-dispatch-fn req)]
      (try+
       (statsd/with-timing (str "ws." (namespace event) "." (name event))
         (ws-handler (assoc req
                            :sente-state sente-state
                            :db (pcd/default-db)
                            :receive-instant (java.util.Date.)
                            ;; TODO: Have to kill sente
                            :client-id client-id)))
       (catch :status t
         (let [send-fn (partial send-msg req)]
           (log/error t)
           ;; TODO: should this use the send-fn? We can do that too, I guess, inside of the defmethod.
           ;; TODO: rip out sente and write a sensible library
           (send-fn client-id [:frontend/error {:status-code (:status t)
                                                :error-msg (:error-msg t)
                                                :error-key (:error-key t)
                                                :event (:event req)
                                                :event-data (:?data req)}])))
       (catch Object e
         (log/error e)

         (.printStackTrace (:throwable &throw-context))
         (rollbar/report-exception (:throwable &throw-context)
                                   :request (:ring-req req)
                                   :cust (some-> req :ring-req :auth :cust)
                                   :extra-data (select-keys req [:?data :event])))))))

(defn setup-ws-handlers [sente-state]
  (let [tap (async/chan (async/sliding-buffer 100))
        mult (async/mult (:ch-recv sente-state))]
    (async/tap mult tap)
    (dotimes [x 10]
      (async/go-loop []
        (when-let [req (async/<! tap)]
          (utils/straight-jacket (handle-req req))
          (recur))))))

(defn convert-to-sente-format [msg]
  (assoc msg
         :client-id (user-id-fn (:tal/ring-req msg))
         :ring-req (:tal/ring-req msg)
         :event [(:op msg) (:data msg)]
         :?data (:data msg)))

(defn handle-tal-msg [msg]
  (utils/with-report-exceptions
    (let [req (convert-to-sente-format msg)
          event (ws-handler-dispatch-fn req)]
      (try+
       (statsd/with-timing (str "ws." (namespace event) "." (name event))
         (ws-handler (assoc req
                            :db (pcd/default-db)
                            :receive-instant (java.util.Date.))))
       (catch :status t
         (log/error t)
         (send-msg req (:client-id req) [:frontend/error {:status-code (:status t)
                                                          :error-msg (:error-msg t)
                                                          :error-key (:error-key t)
                                                          :event (:event req)
                                                          :event-data (:?data req)}]))
       (catch Object e
         (log/error e)

         (.printStackTrace (:throwable &throw-context))
         (rollbar/report-exception (:throwable &throw-context)
                                   :request (:ring-req req)
                                   :cust (some-> req :ring-req :auth :cust)
                                   :extra-data (select-keys req [:?data :event])))))))

(defn setup-tal-handlers []
  (let [tap (tal/tap-msg-ch tal/talaria-state)]
    (dotimes [x 10]
      (async/go-loop []
        (when-let [msg (async/<! tap)]
          (utils/straight-jacket (handle-tal-msg msg))
          (recur))))))

(defn close-ws
  "Closes the websocket, client should reconnect."
  [client-id]
  ((:send-fn @sente-state) client-id [:chsk/close]))

(defn refresh-browser
  "Refreshes the browser if the tab is hidden or if :force is set to true.
   Otherwise, creates a bot chat asking the user to refresh the page."
  [client-id & {:keys [force]
                :or {force false}}]
  ((:send-fn @sente-state) client-id [:frontend/refresh {:force-refresh force}]))

(defn fetch-stats
  "Sends a request to the client for statistics, which is handled above in the :frontend/stats handler"
  [client-id]
  ((:send-fn @sente-state) client-id [:frontend/stats]))

(defn cleanup-stale [doc-id]
  (let [subs @document-subs
        now (time/now)
        doc-uids (keys (get subs doc-id))]
    (doseq [uid doc-uids]
      (fetch-stats uid))
    (Thread/sleep 3000)
    (doseq [[uid stats] (remove (fn [[_ s]]
                                  (and (:last-update s)
                                       (time/after? (:last-update s) now)))
                                (select-keys @client-stats doc-uids))]
      (close-ws uid))))

(defn cleanup-all-stale []
  (let [subs @document-subs
        now (time/now)
        uids (mapcat keys (map second @pc.http.sente/document-subs))]
    (doseq [uid uids]
      (fetch-stats uid))
    (Thread/sleep 10000)
    (doseq [[uid stats] (remove (fn [[_ s]]
                                  (and (:last-update s)
                                       (time/after? (:last-update s) now)))
                                (select-keys @client-stats uids))]
      (close-ws uid))))

(defn init []
  (let [{:keys [ch-recv send-fn ajax-post-fn connected-uids
                ajax-get-or-ws-handshake-fn] :as fns} (sente/make-channel-socket!
                                                       sente-web-server-adapter
                                                       {:user-id-fn #'user-id-fn
                                                        :send-buf-ms-ws 5})]
    (reset! sente-state fns)
    (setup-ws-handlers fns)
    (tal/init)
    (setup-tal-handlers)
    (track-document-subs)
    (track-clients)
    (track-team-subs)
    fns))

(defn shutdown [& {:keys [sleep-ms]
                            :or {sleep-ms 100}}]
  (doseq [client-id (reduce (fn [acc [_ clients]]
                              (apply conj acc (keys clients)))
                            #{} @document-subs)]
    (close-ws client-id)
    (Thread/sleep sleep-ms)))
