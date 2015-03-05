(ns pc.http.sente
  (:require [clojure.core.async :as async]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clj-time.core :as time]
            [clj-statsd :as statsd]
            [datomic.api :refer [db q] :as d]
            [pc.auth :as auth]
            [pc.datomic :as pcd]
            [pc.datomic.web-peer :as web-peer]
            [pc.datomic.schema :as schema]
            [pc.email :as email]
            [pc.http.datomic2 :as datomic2]
            [pc.models.access-grant :as access-grant-model]
            [pc.models.access-request :as access-request-model]
            [pc.models.chat :as chat]
            [pc.models.cust :as cust]
            [pc.models.doc :as doc-model]
            [pc.models.layer :as layer]
            [pc.models.permission :as permission-model]
            [pc.rollbar :as rollbar]
            [pc.utils :as utils]
            [slingshot.slingshot :refer (try+ throw+)]
            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.http-kit :refer (http-kit-adapter)])
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

(defonce client-stats (atom {}))

(defn notify-transaction [data]
  (doseq [[uid _] (dissoc (get @document-subs (:document/id data)) (str (:session/uuid data)))]
    (log/infof "notifying %s about new transactions for %s" uid (:document/id data))
    ((:send-fn @sente-state) uid [:datomic/transaction data]))
  (when-let [server-timestamps (seq (filter #(= :server/timestamp (:a %)) (:tx-data data)))]
    (log/infof "notifying %s about new server timestamp for %s" (:session/uuid data) (:document/id data))
    ((:send-fn @sente-state) (str (:session/uuid data)) [:datomic/transaction (assoc data :tx-data server-timestamps)])))

(defn ws-handler-dispatch-fn [req]
  (-> req :event first))

(defn check-document-access-from-auth [doc-id req scope]
  (let [doc (doc-model/find-by-id (:db req) doc-id)]
    (when-not (auth/has-document-permission? (:db req) doc (-> req :ring-req :auth) scope)
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

;; TODO: this should take an access level at some point
(defn check-document-access [doc-id req scope]
  (or (check-subscribed doc-id req scope)
      (check-document-access-from-auth doc-id req scope)))

(defmulti ws-handler ws-handler-dispatch-fn)

(defmethod ws-handler :default [req]
  (log/infof "%s for %s" (:event req) (:client-id req)))

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
                                 ds ds)))
  (swap! client-stats dissoc client-id))

(defn close-connection [client-id]
  (log/infof "closing connection for %s" client-id)
  (doseq [uid (reduce (fn [acc [doc-id clients]]
                        (if (contains? clients client-id)
                          (set/union acc (keys clients))
                          acc))
                      #{} @document-subs)]
    (log/infof "notifying %s about %s leaving" uid client-id)
    ((:send-fn @sente-state) uid [:frontend/subscriber-left {:client-id client-id}]))
  (clean-document-subs client-id))

(defmethod ws-handler :chsk/uidport-close [{:keys [client-id] :as req}]
  (close-connection client-id))

(defmethod ws-handler :frontend/close-connection [{:keys [client-id] :as req}]
  (close-connection client-id))

(defmethod ws-handler :frontend/unsubscribe [{:keys [client-id ?data ?reply-fn] :as req}]
  (check-document-access (-> ?data :document-id) req :admin)
  (let [document-id (-> ?data :document-id)]
    (log/infof "unsubscribing %s from %s" client-id document-id)
    (close-connection client-id)
    (doseq [[uid _] (get @document-subs document-id)]
      ((:send-fn @sente-state) uid [:frontend/subscriber-left {:client-id client-id}]))))

(def colors
  #{"#1abc9c"
    "#2ecc71"
    "#3498db"
    "#9b59b6"
    "#16a085"
    "#27ae60"
    "#2980b9"
    "#8e44ad"
    "#f1c40f"
    "#e67e22"
    "#e74c3c"
    "#f39c12"
    "#d35400"
    "#c0392b"
    ;;"#ecf0f1"
    ;;"#bdc3c7"
    })

(defn choose-color [subs client-id requested-color]
  (let [available-colors (or (seq (apply disj colors (map :color (vals subs))))
                             (seq colors))]
    (or (get-in subs [client-id :color])
        (when (not= -1 (.indexOf available-colors requested-color))
          requested-color)
        (rand-nth available-colors))))

(defn choose-frontend-id-seed [db document-id subs requested-remainder]
  (let [available-remainders (apply disj web-peer/remainders (map (comp :remainder :frontend-id-seed)
                                                                  (vals subs)))]
    (if-let [remainder (if (contains? available-remainders requested-remainder)
                         requested-remainder
                         (first available-remainders))]
      (let [used-client-parts (web-peer/client-parts-for-ns db document-id)
            ;; do something to protect against over 100K eids
            ;; XXX should we ensure in the transactor that ids increase properly?
            used-from-partition (set (filter #(= remainder (mod % web-peer/multiple)) used-client-parts))
            start-eid (first (remove #(contains? used-from-partition %)
                                     (iterate (partial + web-peer/multiple) (+ web-peer/multiple remainder))))]
        {:remainder remainder :multiple web-peer/multiple :next-id start-eid})
      (throw+ {:status 403
               :error-msg "There are too many users in the document."
               :error-key :too-many-subscribers}))))

;; XXX need to add requested remainder also
(defn subscribe-to-doc [db document-id uuid cust & {:keys [requested-color requested-remainder]}]
  (swap! client-stats assoc uuid {:document {:db/id document-id}})
  (swap! document-subs update-in [document-id]
         (fn [subs]
           (-> subs
             (assoc-in [uuid :color] (choose-color subs uuid requested-color))
             (assoc-in [uuid :client-id] uuid)
             (update-in [uuid] merge (select-keys cust [:cust/uuid :cust/color-name :cust/name]))
             (assoc-in [uuid :show-mouse?] true)
             (assoc-in [uuid :frontend-id-seed] (choose-frontend-id-seed db document-id subs requested-remainder))))))

;; TODO: subscribe should be the only function you need when you get to a doc, then it should send
;;       all of the data asynchronously
(defmethod ws-handler :frontend/subscribe [{:keys [client-id ?data ?reply-fn] :as req}]
  (check-document-access (-> ?data :document-id) req :admin)
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
    (log/infof "sending permission-data for %s to %s" document-id client-id)
    (send-fn client-id [:frontend/db-entities
                        {:document/id document-id
                         :entities (map (partial permission-model/read-api (:db req))
                                        (filter :permission/cust
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
                         :entity-type :access-request}])))

(defmethod ws-handler :frontend/fetch-created [{:keys [client-id ?data ?reply-fn] :as req}]
  (when-let [cust (-> req :ring-req :auth :cust)]
    (let [;; TODO: at some point we may want to limit, but it's just a
          ;; list of longs, so meh
          ;; limit (get ?data :limit 100)
          ;; offset (get ?data :offset 0)
          doc-ids (doc-model/find-created-by-cust (:db req) cust)]
      (log/infof "fetching created for %s" client-id)
      (?reply-fn {:docs (map (fn [doc-id] {:db/id doc-id
                                           :last-updated-instant (doc-model/last-updated-time (:db req) doc-id)})
                             doc-ids)}))))

(defmethod ws-handler :frontend/fetch-touched [{:keys [client-id ?data ?reply-fn] :as req}]
  (when-let [cust (-> req :ring-req :auth :cust)]
    (let [;; TODO: at some point we may want to limit, but it's just a
          ;; list of longs, so meh
          ;; limit (get ?data :limit 100)
          ;; offset (get ?data :offset 0)
          doc-ids (doc-model/find-touched-by-cust (:db req) cust)]
      (log/infof "fetching touched for %s" client-id)
      (?reply-fn {:docs (map (fn [doc-id] {:db/id doc-id
                                           :last-updated-instant (doc-model/last-updated-time (:db req) doc-id)})
                             doc-ids)}))))

(defmethod ws-handler :frontend/transaction [{:keys [client-id ?data] :as req}]
  (check-document-access (-> ?data :document/id) req :admin)
  (let [document-id (-> ?data :document/id)
        datoms (->> ?data
                 :datoms
                 (remove (comp nil? :v))
                 ;; Don't let people sneak layers into other documents
                 (map (fn [d] (if (= :document/id (:a d))
                                (assoc d :v document-id)
                                d))))
        _ (def datoms datoms)
        cust-uuid (-> req :ring-req :auth :cust :cust/uuid)]
    (log/infof "transacting %s on %s for %s" datoms document-id client-id)
    (datomic2/transact! datoms
                        {:document-id document-id
                         :client-id client-id
                         :cust-uuid cust-uuid
                         :session-uuid (UUID/fromString (get-in req [:ring-req :session :sente-id]))})))

(defmethod ws-handler :frontend/mouse-position [{:keys [client-id ?data] :as req}]
  (check-document-access (-> ?data :document/id) req :admin)
  (let [document-id (-> ?data :document/id)
        mouse-position (-> ?data :mouse-position)
        tool (-> ?data :tool)
        layers (-> ?data :layers)]
    (doseq [[uid _] (dissoc (get @document-subs document-id) client-id)]
      ((:send-fn @sente-state) uid [:frontend/mouse-move (merge
                                                          {:client-id client-id
                                                           :tool tool
                                                           :layers layers}
                                                          (when mouse-position
                                                            {:mouse-position mouse-position}))]))))

(defmethod ws-handler :frontend/update-self [{:keys [client-id ?data] :as req}]
  ;; TODO: update subscribers in a different way
  (check-document-access (-> ?data :document/id) req :admin)
  (when-let [cust (-> req :ring-req :auth :cust)]
    (let [doc-id (-> ?data :document/id)]
      (log/infof "updating self for %s" (:cust/uuid cust))
      (when (:cust/color-name ?data)
        (assert (contains? (schema/color-enums) (:cust/color-name ?data))))
      (let [new-cust (cust/update! cust (select-keys ?data [:cust/name :cust/color-name]))]
        ;; XXX: do this cross-document
        (doseq [uid (reduce (fn [acc subs]
                              (if (first (filter #(= (:cust/uuid (second %)) (:cust/uuid new-cust))
                                                 subs))
                                (concat acc (keys subs))
                                acc))
                            () (vals @document-subs))]
          ;; TODO: use update-subscriber for everything
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
                                                           :document/id doc-id
                                                           :transaction/broadcast true}
                                                          (when cust
                                                            {:cust/uuid (:cust/uuid cust)}))
                                                   (web-peer/server-frontend-id chat-id doc-id)
                                                   {:chat/body body
                                                    :server/timestamp (java.util.Date.)
                                                    :document/id doc-id
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
        _ (assert (contains? (:flags cust) :flags/private-docs))
        ;; letting datomic's schema do validation for us, might be a bad idea?
        setting (-> ?data :setting)
        annotations {:document/id doc-id
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
            annotations {:document/id doc-id
                         :cust/uuid (:cust/uuid cust)
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

(defmethod ws-handler :frontend/grant-access-request [{:keys [client-id ?data ?reply-fn] :as req}]
  (check-document-access (-> ?data :document/id) req :admin)
  (let [doc-id (-> ?data :document/id)]
    (if-let [request (some->> ?data :request-id (access-request-model/find-by-client-part (:db req) doc-id))]
      (let [cust (-> req :ring-req :auth :cust)
            annotations {:document/id doc-id
                         :cust/uuid (:cust/uuid cust)
                         :transaction/broadcast true}]
        ;; TODO: need better permissions checking here. Maybe IAM-type roles for each entity?
        ;;       Right now it's too easy to accidentally forget to check.
        (assert (= doc-id (:access-request/document request)))
        (permission-model/convert-access-request request cust annotations))
      (comment (notify-invite "Please sign up to send an invite.")))))

(defmethod ws-handler :frontend/deny-access-request [{:keys [client-id ?data ?reply-fn] :as req}]
  (check-document-access (-> ?data :document/id) req :admin)
  (let [doc-id (-> ?data :document/id)]
    (if-let [request (some->> ?data :request-id (access-request-model/find-by-client-part (:db req) doc-id))]
      (let [cust (-> req :ring-req :auth :cust)
            annotations {:document/id doc-id
                         :cust/uuid (:cust/uuid cust)
                         :transaction/broadcast true}]
        ;; TODO: need better permissions checking here. Maybe IAM-type roles for each entity?
        ;;       Right now it's too easy to accidentally forget to check.
        (assert (= doc-id (:access-request/document request)))
        (access-request-model/deny-request request annotations))
      (comment (notify-invite "Please sign up to send an invite.")))))

;; TODO: don't send request if they already have access
(defmethod ws-handler :frontend/send-permission-request [{:keys [client-id ?data ?reply-fn] :as req}]
  (let [doc-id (-> ?data :document/id)]
    (if-let [cust (-> req :ring-req :auth :cust)]
      (if-let [doc (doc-model/find-by-id (:db req) doc-id)]
        (let [email (-> ?data :email)
              annotations {:document/id doc-id
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

(defmethod ws-handler :frontend/save-browser-settings [{:keys [client-id ?data ?reply-fn] :as req}]
  (if-let [cust (-> req :ring-req :auth :cust)]
    (let [settings (-> ?data :settings)]
      (log/infof "saving browser settings for %s" (:cust/email cust))
      @(d/transact (pcd/conn) [(merge {:db/id (:db/id cust)}
                                      (select-keys settings (schema/browser-setting-idents)))]))
    (let [msg (format "no cust for %s" (:remote-addr (:ring-req req)))]
      (rollbar/report-exception (Exception. msg) :request (:ring-req req) :cust (some-> req :ring-req :auth :cust))
      (log/errorf msg))))

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
                                                       http-kit-adapter
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
