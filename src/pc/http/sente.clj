(ns pc.http.sente
  (:require [clojure.core.async :as async]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.api :refer [db q] :as d]
            [pc.http.datomic2 :as datomic]
            [pc.models.layer :as layer]
            [pc.datomic :as pcd]
            [taoensso.sente :as sente])
  (:import java.util.UUID))

;; TODO: find a way to restart sente
(defonce sente-state (atom {}))

(defn uuid
  "Have to remove - so that we can parse it out of the client-uuid"
  []
  (UUID/randomUUID))

(defn user-id-fn [req]
  (let [uid (get-in req [:session :uid])]
    ;; have to stringify this for sente for comparisons to work
    (str uid)))

(defn wrap-user-id [handler]
  (fn [req]
    (handler
     (if-not (get-in req [:session :uid])
       (assoc-in req [:session :uid] (uuid))
       req))))

;; hash-map of document-id to connected users
;; Used to keep track of which transactions to send to which user
;; sente's channel handling stuff is not much fun to work with :(
;; e.g {:12345 {:uuid-1 {show-mouse?: true} :uuid-1 {:show-mouse? false}}}
(defonce document-subs (atom {}))

(defn notify-transaction [data]
  ;; TODO: store client-uuid as a proper uuid everywhere
  (doseq [[uid _] (dissoc (get @document-subs (:document/id data)) (str (:session/uuid data)))]
    (log/infof "notifying %s about new transactions for %s" uid (:document/id data))
    ((:send-fn @sente-state) uid [:datomic/transaction data])))

(defn ws-handler-dispatch-fn [req]
  (-> req :event first))

(defn client-uuid->uuid
  "Get the client's user-id from the client-uuid"
  [client-uuid]
  (str/replace client-uuid #"-[^-]+$" ""))

(defmulti ws-handler ws-handler-dispatch-fn)

(defmethod ws-handler :default [req]
  (log/infof "%s for %s" (:event req) (:client-uuid req)))

(defn clean-document-subs [uuid]
  (swap! document-subs (fn [ds]
                         ;; Could be optimized...
                         (reduce (fn [acc [document-id user-ids]]
                                   (if-not (contains? user-ids uuid)
                                     acc
                                     (let [new-user-ids (dissoc user-ids uuid)]
                                       (if (empty? new-user-ids)
                                         (dissoc acc document-id)
                                         (assoc acc document-id new-user-ids)))))
                                 ds ds))))

(defmethod ws-handler :chsk/uidport-close [{:keys [client-uuid] :as req}]
  (log/infof "closing connection for %s" client-uuid)
  (let [uuid (client-uuid->uuid client-uuid)]
    (doseq [uid (reduce (fn [acc [doc-id clients]]
                          (if (contains? clients uuid)
                            (set/union acc (keys clients))
                            acc))
                        #{} @document-subs)]
      (log/infof "notifying %s about %s leaving" uid uuid)
      ((:send-fn @sente-state) uid [:frontend/subscriber-left {:client-uuid uuid}]))
    (clean-document-subs uuid)))

(defn subscribe-to-doc [document-id uuid]
  (swap! document-subs update-in [document-id] (fnil assoc {}) uuid {}))

(defmethod ws-handler :frontend/subscribe [{:keys [client-uuid ?data ?reply-fn] :as req}]
  (let [document-id (-> ?data :document-id)
        db (pcd/default-db)
        cid (client-uuid->uuid client-uuid)]
    (log/infof "subscribing %s to %s" client-uuid document-id)
    (doseq [[uid _] (dissoc (get @document-subs document-id) cid)]
      ((:send-fn @sente-state) uid [:frontend/subscriber-joined {:client-uuid cid}]))
    (subscribe-to-doc document-id (client-uuid->uuid client-uuid))
    (let [resp {:layers (layer/find-by-document db {:db/id document-id})
                :document (pcd/touch+ (d/entity db document-id))
                :client-uuid (client-uuid->uuid client-uuid)}]
      (?reply-fn resp))))

(defmethod ws-handler :frontend/fetch-subscribers [{:keys [client-uuid ?data ?reply-fn] :as req}]
  (let [document-id (-> ?data :document-id)]
    (log/infof "fetching subscribers for %s on %s" client-uuid document-id)
    (subscribe-to-doc document-id (client-uuid->uuid client-uuid))
    (?reply-fn {:subscribers (get @document-subs document-id)})))

(defmethod ws-handler :frontend/transaction [{:keys [client-uuid ?data] :as req}]
  (let [document-id (-> ?data :document/id)
        datoms (->> ?data :datoms (remove (comp nil? :v)))]
    (log/infof "transacting %s on %s for %s" datoms document-id client-uuid)
    (datomic/transact! datoms document-id (UUID/fromString (client-uuid->uuid client-uuid)))))

(defmethod ws-handler :frontend/mouse-position [{:keys [client-uuid ?data] :as req}]
  (let [document-id (-> ?data :document/id)
        mouse-position (-> ?data :mouse-position)
        tool (-> ?data :tool)
        layer (-> ?data :layer)
        cid (client-uuid->uuid client-uuid)]
    (doseq [[uid _] (dissoc (get @document-subs document-id) cid)]
      ((:send-fn @sente-state) uid [:frontend/mouse-move (merge
                                                          {:client-uuid cid
                                                           :tool tool
                                                           :layer layer}
                                                          (when mouse-position
                                                            {:mouse-position mouse-position}))]))))

;; TODO: maybe need a compare-and-set! here
(defmethod ws-handler :frontend/share-mouse [{:keys [client-uuid ?data] :as req}]
  (let [document-id (-> ?data :document/id)
        show-mouse? (-> ?data :show-mouse?)
        mouse-owner-uuid (-> ?data :mouse-owner-uuid)
        cid (client-uuid->uuid client-uuid)]
    (log/infof "toggling share-mouse to %s from %s for %s" show-mouse? mouse-owner-uuid cid)
    (swap! document-subs (fn [subs]
                           (if (get-in subs [document-id mouse-owner-uuid])
                             (assoc-in subs [document-id mouse-owner-uuid :show-mouse?] show-mouse?)
                             subs)))
    (doseq [[uid _] (get @document-subs document-id)]
      ((:send-fn @sente-state) uid [:frontend/share-mouse {:client-uuid cid
                                                           :show-mouse? show-mouse?
                                                           :mouse-owner-uuid mouse-owner-uuid}]))))

(defmethod ws-handler :chsk/ws-ping [req]
  ;; don't log
  nil)

(defn setup-ws-handlers [sente-state]
  (let [tap (async/chan (async/sliding-buffer 100))
        mult (async/mult (:ch-recv sente-state))]
    (async/tap mult tap)
    (async/go-loop []
                   (when-let [req (async/<! tap)]
                     (try
                       (ws-handler req)
                       (catch Exception e
                         (log/error e)))
                     (recur)))))

(defn init []
  (let [{:keys [ch-recv send-fn ajax-post-fn connected-uids
                ajax-get-or-ws-handshake-fn] :as fns} (sente/make-channel-socket! {:user-id-fn #'user-id-fn})]
    (reset! sente-state fns)
    (setup-ws-handlers fns)
    fns))
