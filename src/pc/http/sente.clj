(ns pc.http.sente
  (:require [clj-statsd :as statsd]
            [clj-time.core :as time]
            [clojure.core.async :as async]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.api :refer [db q] :as d]
            [pc.auth :as auth]
            [pc.datomic :as pcd]
            [pc.datomic.schema :as schema]
            [pc.datomic.web-peer :as web-peer]
            [pc.email :as email]
            [pc.http.datomic2 :as datomic2]
            [pc.http.datomic.common :as datomic-common]
            [pc.models.access-grant :as access-grant-model]
            [pc.models.access-request :as access-request-model]
            [pc.models.chat :as chat]
            [pc.models.cust :as cust]
            [pc.models.doc :as doc-model]
            [pc.models.layer :as layer]
            [pc.models.permission :as permission-model]
            [pc.models.team :as team-model]
            [pc.replay :as replay]
            [pc.rollbar :as rollbar]
            [pc.utils :as utils]
            [slingshot.slingshot :refer (try+ throw+)]
            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.immutant :refer (sente-web-server-adapter)])
  (:import java.util.UUID))

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

  (when (empty? (get-in req [:params :tab-id]))
    (let [msg (format "tab-id is nil for %s on %s" (:remote-addr req) (:uri req))]
      (rollbar/report-exception (Exception. msg) :request (:ring-req req) :cust (some-> req :ring-req :auth :cust))
      (log/errorf msg)))

  (str (get-in req [:session :sente-id])
       "-"
       (get-in req [:params :tab-id])))

;; hash-map of document-id to connected users
;; Used to keep track of which transactions to send to which user
;; sente's channel handling stuff is not much fun to work with :(
;; e.g {:12345 {:uuid-1 {show-mouse?: true} :uuid-1 {:show-mouse? false}}}
(defonce document-subs (atom {}))
(defonce team-subs (atom {}))

(defonce client-stats (atom {}))

(defn notify-document-transaction [data]
  (let [doc-id (:db/id (:transaction/document data))]
    (doseq [[uid _] (dissoc (get @document-subs doc-id) (:session/client-id data))]
      (log/infof "notifying %s about new transactions for %s" uid doc-id)
      ((:send-fn @sente-state) uid [:datomic/transaction data]))
    (when-let [server-timestamps (seq (filter #(= :server/timestamp (:a %)) (:tx-data data)))]
      (log/infof "notifying %s about new server timestamp for %s" (:session/uuid data) doc-id)
      ((:send-fn @sente-state) (str (:session/client-id data)) [:datomic/transaction (assoc data :tx-data server-timestamps)]))))

(defn notify-team-transaction [data]
  (let [team-uuid (:team/uuid (:transaction/team data))]
    (doseq [[uid _] (dissoc (get @team-subs team-uuid) (:session/client-id data))]
      (log/infof "notifying %s about new team transactions for %s" uid team-uuid)
      ((:send-fn @sente-state) uid [:team/transaction data]))
    (when-let [server-timestamps (seq (filter #(= :server/timestamp (:a %)) (:tx-data data)))]
      (log/infof "notifying %s about new team server timestamp for %s" (:session/uuid data) team-uuid)
      ((:send-fn @sente-state) (str (:session/client-id data)) [:team/transaction (assoc data :tx-data server-timestamps)]))))

(defn ws-handler-dispatch-fn [req]
  (-> req :event first))

(defn has-document-access? [doc-id req scope]
  (let [doc (doc-model/find-by-id (:db req) doc-id)]
    (auth/has-document-permission? (:db req) doc (-> req :ring-req :auth) scope)))

(defn check-document-access-from-auth [doc-id req scope]
  (time (when-not (has-document-access? doc-id req scope)
          (if (auth/logged-in? (:ring-req req))
            (throw+ {:status 403
                     :error-msg "This document is private. Please request access."
                     :error-key :document-requires-invite})
            (throw+ {:status 401
                     :error-msg "This document is private. Please log in to access it."
                     :error-key :document-requires-login})))))

;; TODO: make sure to kick the user out of subscribed if he loses access
(defn check-subscribed [doc-id req scope]
  ;; TODO: we're making a simplifying assumption that subscribed == :admin
  ;;       That needs to be fixed at some point
  (when (= scope :admin)
    (get-in @document-subs [doc-id (-> req :client-id)])))

(defn check-document-access [doc-id req scope]
  {:pre [doc-id]}
  (check-document-access-from-auth doc-id req scope))

(defn check-team-subscribed [team-uuid req scope]
  (when (= scope :admin)
    (get-in @team-subs [team-uuid (-> req :client-id)])))

(defn check-team-access-from-auth [team-uuid req scope]
  (let [team (team-model/find-by-uuid (:db req) team-uuid)]
    (when-not (auth/has-team-permission? (:db req) team (-> req :ring-req :auth) scope)
      (if (auth/logged-in? (:ring-req req))
        (throw+ {:status 403
                 :error-msg "This team is private. Please request access."
                 :error-key :team-requires-invite})
        (throw+ {:status 401
                 :error-msg "This team is private. Please log in to access it."
                 :error-key :team-requires-login})))))

(defn check-team-access [team-uuid req scope]
  {:pre [team-uuid]}
  (or (check-team-subscribed team-uuid req scope)
      (check-team-access-from-auth team-uuid req scope)))

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
    ((:send-fn @sente-state) uid [:frontend/subscriber-left {:client-id client-id}]))
  (clean-team-subs client-id)
  (clean-document-subs client-id)
  (swap! client-stats dissoc client-id))

(defn subscribe-to-team [team-uuid uuid cust]
  (swap! client-stats update-in [uuid] merge {:team {:db/id team-uuid}})
  (swap! team-subs update-in [team-uuid]
         (fn [subs]
           (-> subs
             (assoc-in [uuid] {:client-id uuid
                               :cust/uuid (:cust/uuid cust)})))))

(defmulti ws-handler ws-handler-dispatch-fn)

(defmethod ws-handler :default [req]
  (log/infof "%s for %s" (:event req) (:client-id req)))

(defmethod ws-handler :chsk/uidport-close [{:keys [client-id] :as req}]
  (close-connection client-id))

(defmethod ws-handler :frontend/close-connection [{:keys [client-id] :as req}]
  (close-connection client-id))

(defmethod ws-handler :frontend/unsubscribe [{:keys [client-id ?data ?reply-fn] :as req}]
  (check-document-access (-> ?data :document-id) req :read)
  (let [document-id (-> ?data :document-id)]
    (log/infof "unsubscribing %s from %s" client-id document-id)
    (close-connection client-id)
    (doseq [[uid _] (get @document-subs document-id)]
      ((:send-fn @sente-state) uid [:frontend/subscriber-left {:client-id client-id}]))))

(defmethod ws-handler :team/subscribe [{:keys [client-id ?data ?reply-fn] :as req}]
  (let [team-uuid (-> ?data :team/uuid)
        team (team-model/find-by-uuid (:db req) team-uuid)
        send-fn (:send-fn @sente-state)]
    (check-team-access team-uuid req :admin)
    (subscribe-to-team team-uuid client-id (get-in req [:ring-req :auth :cust]))

    (log/infof "sending permission-data for team %s to %s" (:team/subdomain team) client-id)
    (send-fn client-id [:team/db-entities
                        {:team/uuid team-uuid
                         :entities (map (partial permission-model/read-api (:db req))
                                        (filter :permission/cust-ref
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
                         :entity-type :access-request}])))

;; TODO: subscribe should be the only function you need when you get to a doc, then it should send
;;       all of the data asynchronously
(defmethod ws-handler :frontend/subscribe [{:keys [client-id ?data ?reply-fn] :as req}]
  (try+
   (check-document-access (-> ?data :document-id) req :read)
   (catch :status t
     (?reply-fn [:subscribe/error])
     (throw+)))
  (let [document-id (-> ?data :document-id)
        send-fn (:send-fn @sente-state)
        _ (log/infof "subscribing %s to %s" client-id document-id)
        subs (subscribe-to-doc (:db req)
                               document-id
                               client-id
                               (-> req :ring-req :auth :cust)
                               :requested-color (:requested-color ?data)
                               :requested-remainder (:requested-remainder ?data))]

    (?reply-fn [:frontend/frontend-id-state {:frontend-id-state (get-in subs [document-id client-id :frontend-id-seed])}])

    (doseq [[uid _] (get @document-subs document-id)]
      (send-fn uid [:frontend/subscriber-joined (merge {:client-id client-id}
                                                       (get-in subs [document-id client-id]))]))

    ;; TODO: we'll need a read-api or something here at some point
    (log/infof "sending document for %s to %s" document-id client-id)
    (send-fn client-id [:frontend/db-entities
                        {:document/id document-id
                         :entities [(doc-model/read-api (doc-model/find-by-id (:db req) document-id))]
                         :entity-type :document}])

    (log/infof "sending layers for %s to %s" document-id client-id)
    (send-fn client-id [:frontend/db-entities
                        {:document/id document-id
                         :entities (map layer/read-api (layer/find-by-document (:db req) {:db/id document-id}))
                         :entity-type :layer}])

    (log/infof "sending custs for %s to %s" document-id client-id)
    (send-fn client-id [:frontend/custs
                        {:document/id document-id
                         :uuid->cust (->> document-id
                                       (cust/cust-uuids-for-doc (:db req))
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
                         :subscribers (get subs document-id)}])

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

(defmethod ws-handler :frontend/fetch-touched [{:keys [client-id ?data ?reply-fn] :as req}]
  (when-let [cust (-> req :ring-req :auth :cust)]
    (let [;; TODO: at some point we may want to limit, but it's just a
          ;; list of longs, so meh
          ;; limit (get ?data :limit 100)
          ;; offset (get ?data :offset 0)
          doc-ids (if-let [team (get-in req [:ring-req :team])]
                    (doc-model/find-touched-by-cust-in-team (:db req) cust team)
                    (doc-model/find-touched-by-cust (:db req) cust))]
      (log/infof "fetched %s touched for %s" (count doc-ids) client-id)
      (?reply-fn {:docs (map (fn [doc-id] {:db/id doc-id
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
      (?reply-fn {:docs (map (fn [doc-id] {:db/id doc-id
                                           :last-updated-instant (doc-model/last-updated-time (:db req) doc-id)})
                             doc-ids)}))))

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

(defmethod ws-handler :frontend/transaction [{:keys [client-id ?data] :as req}]
  (check-document-access (-> ?data :document/id) req :admin)
  (def mydata ?data)
  (let [document-id (-> ?data :document/id)
        datoms (->> ?data
                 :datoms
                 (remove (comp nil? :v))
                 ;; Don't let people sneak layers into other documents
                 (reduce (fn [acc d]
                           (cond (= "document" (name (:a d)))
                                 (conj acc
                                       (assoc d :v document-id)
                                       (assoc d :a :document/id :v document-id))
                                 (= :document/id (:a d))
                                 (conj acc
                                       (assoc d :v document-id)
                                       (assoc d :a (determine-type d (:datoms ?data)) :v document-id))
                                 :else (conj acc d)))
                         []))
        _ (def mydatoms datoms)
        cust-uuid (-> req :ring-req :auth :cust :cust/uuid)]
    (log/infof "transacting %s datoms on %s for %s" (count datoms) document-id client-id)
    (datomic2/transact! datoms
                        {:document-id document-id
                         :client-id client-id
                         :cust-uuid cust-uuid
                         :session-uuid (UUID/fromString (get-in req [:ring-req :session :sente-id]))})))

(defmethod ws-handler :team/transaction [{:keys [client-id ?data] :as req}]
  (check-team-access (-> ?data :team/uuid) req :admin)
  (let [team (team-model/find-by-uuid (:db req) (:team/uuid ?data))
        datoms (->> ?data
                 :datoms
                 (remove (comp nil? :v))
                 ;; Don't let people sneak into other teams
                 (map (fn [d]
                        (if (= "team" (name (:a d)))
                          (assoc d :v (:db/id team))
                          d))))
        cust-uuid (-> req :ring-req :auth :cust :cust/uuid)]
    (log/infof "transacting %s datoms on %s for %s" (count datoms) (:team/uuid team) client-id)
    (datomic2/transact! datoms
                        {:team-id (:db/id team)
                         :client-id client-id
                         :cust-uuid cust-uuid
                         :session-uuid (UUID/fromString (get-in req [:ring-req :session :sente-id]))})))

(defmethod ws-handler :frontend/mouse-position [{:keys [client-id ?data] :as req}]
  (check-document-access (-> ?data :document/id) req :read)
  (let [document-id (-> ?data :document/id)
        mouse-position (-> ?data :mouse-position)
        tool (-> ?data :tool)
        layers (-> ?data :layers)
        relation (-> ?data :relation)]
    (doseq [[uid _] (dissoc (get @document-subs document-id) client-id)]
      ((:send-fn @sente-state) uid [:frontend/mouse-move (merge
                                                          {:client-id client-id
                                                           :tool tool
                                                           :layers layers
                                                           :relation relation}
                                                          (when mouse-position
                                                            {:mouse-position mouse-position}))]))))

(defmethod ws-handler :frontend/update-self [{:keys [client-id ?data] :as req}]
  ;; TODO: update subscribers in a different way
  (check-document-access (-> ?data :document/id) req :read)
  (when-let [cust (-> req :ring-req :auth :cust)]
    (let [doc-id (-> ?data :document/id)]
      (log/infof "updating self for %s" (:cust/uuid cust))
      (when (:cust/color-name ?data)
        (assert (contains? (schema/color-enums) (:cust/color-name ?data))))
      (let [new-cust (cust/update! cust (select-keys ?data [:cust/name :cust/color-name]))]
        (doseq [uid (reduce (fn [acc subs]
                              (if (first (filter #(= (:cust/uuid (second %)) (:cust/uuid new-cust))
                                                 subs))
                                (concat acc (keys subs))
                                acc))
                            () (vals @document-subs))]
          ((:send-fn @sente-state) uid [:frontend/custs {:uuid->cust {(:cust/uuid new-cust) (cust/public-read-api new-cust)}}]))))))

(defmethod ws-handler :frontend/send-invite [{:keys [client-id ?data ?reply-fn] :as req}]
  ;; This may turn out to be a bad idea, but error handling is done through creating chats
  (check-document-access (-> ?data :document/id) req :admin)
  (let [doc-id (-> ?data :document/id)
        invite-loc (-> ?data :invite-loc)
        chat-id (d/tempid :db.part/user)
        cust (-> req :ring-req :auth :cust)
        notify-invite (fn [body]
                        (if (= :overlay invite-loc)
                          ((:send-fn @sente-state) client-id
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
                                                    :chat/document doc-id
                                                    :db/id chat-id
                                                    :cust/uuid (auth/prcrsr-bot-uuid (:db req))
                                                    ;; default bot color, also used on frontend chats
                                                    :chat/color "#00b233"}])))]
    (if-let [cust (-> req :ring-req :auth :cust)]
      (let [email (-> ?data :email)]
        (log/infof "%s sending an email to %s on doc %s" (:cust/email cust) email doc-id)
        (try
          (email/send-chat-invite {:cust cust :to-email email :doc-id doc-id})
          (notify-invite (str "Invite sent to " email))
          (catch Exception e
            (rollbar/report-exception e :request (:ring-req req) :cust (some-> req :ring-req :auth :cust))
            (log/error e)
            (.printStackTrace e)
            (notify-invite (str "Sorry! There was a problem sending the invite to " email)))))

      (notify-invite "Please sign up to send an invite."))))

(defmethod ws-handler :frontend/change-privacy [{:keys [client-id ?data ?reply-fn] :as req}]
  (check-document-access (-> ?data :document/id) req :owner)
  (let [doc-id (-> ?data :document/id)
        cust (-> req :ring-req :auth :cust)
        ;; XXX: only if they try to change it to private
        _ (assert (contains? (:flags cust) :flags/private-docs))
        ;; letting datomic's schema do validation for us, might be a bad idea?
        setting (-> ?data :setting)
        annotations {:transaction/document doc-id
                     :cust/uuid (:cust/uuid cust)
                     :transaction/broadcast true}
        txid (d/tempid :db.part/tx)]
    (d/transact (pcd/conn) [(assoc annotations :db/id txid)
                            [:db/add doc-id :document/privacy setting]])))

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

(defmethod ws-handler :frontend/replay-transactions [{:keys [client-id ?data ?reply-fn] :as req}]
  (check-document-access (-> ?data :document/id) req :read)
  (let [doc (->> ?data :document/id (doc-model/find-by-id (:db req)))]
    (let [txes (replay/get-document-transactions (:db req) doc)]
      (doseq [tx txes]
        ((:send-fn @sente-state) client-id [:datomic/transaction (datomic-common/frontend-document-transaction tx)])
        (Thread/sleep (min 500 (get ?data :sleep-ms 250)))))))

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
            ((:send-fn @sente-state) client-id [:frontend/db-entities
                                                {:document/id doc-id
                                                 :entities (map (partial access-request-model/requester-read-api db-after)
                                                                (access-request-model/find-by-doc-and-cust db-after doc cust))
                                                 :entity-type :access-request}]))))
      (comment (notify-invite "Please sign up to send an invite.")))))

(defmethod ws-handler :team/send-permission-request [{:keys [client-id ?data ?reply-fn] :as req}]
  (let [team-uuid (-> ?data :team/uuid)]
    (check-team-access team-uuid req :admin)
    (let [team (team-model/find-by-uuid (:db req) team-uuid)]
      (if-let [cust (-> req :ring-req :auth :cust)]
        (let [email (-> ?data :email)
              annotations {:transaction/team (:db/id team)
                           :cust/uuid (:cust/uuid cust)
                           :transaction/broadcast true}]
          (let [{:keys [db-after]} (access-request-model/create-team-request team cust annotations)]
            ;; have to send it manually to the requestor b/c user won't be subscribed
            ((:send-fn @sente-state) client-id [:team/db-entities
                                                {:team/uuid (:team/uuid team)
                                                 :entities (map (partial access-request-model/requester-read-api db-after)
                                                                (access-request-model/find-by-team-and-cust db-after team cust))
                                                 :entity-type :access-request}])))
        (comment (notify-invite "Please sign up to send an invite."))))))

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
  (?reply-fn [:server/timestamp {:date (java.util.Date.)}]))

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
                            :db (pcd/default-db)
                            ;; TODO: Have to kill sente
                            :client-id client-id)))
       (catch :status t
         (let [send-fn (:send-fn @sente-state)]
           (log/error t)
           ;; TODO: should this use the send-fn? We can do that too, I guess, inside of the defmethod.
           ;; TODO: rip out sente and write a sensible library
           (send-fn client-id [:frontend/error {:status-code (:status t)
                                                :error-msg (:error-msg t)
                                                :event (:event req)
                                                :event-data (:?data req)}])))
       (catch Object e
         (log/error e)

         (.printStackTrace (:throwable &throw-context))
         (rollbar/report-exception e :request (:ring-req req) :cust (some-> req :ring-req :auth :cust)))))))

(defn setup-ws-handlers [sente-state]
  (let [tap (async/chan (async/sliding-buffer 100))
        mult (async/mult (:ch-recv sente-state))]
    (async/tap mult tap)
    (async/go-loop []
                   (when-let [req (async/<! tap)]
                     (utils/straight-jacket (handle-req req))
                     (recur)))))

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

(defn init []
  (let [{:keys [ch-recv send-fn ajax-post-fn connected-uids
                ajax-get-or-ws-handshake-fn] :as fns} (sente/make-channel-socket!
                                                       sente-web-server-adapter
                                                       {:user-id-fn #'user-id-fn})]
    (reset! sente-state fns)
    (setup-ws-handlers fns)
    fns))

(defn shutdown [& {:keys [sleep-ms]
                            :or {sleep-ms 100}}]
  (doseq [client-id (reduce (fn [acc [_ clients]]
                              (apply conj acc (keys clients)))
                            #{} @document-subs)]
    (close-ws client-id)
    (Thread/sleep sleep-ms)))
