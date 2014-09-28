(ns pc.server
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [clojure.tools.reader.edn :as edn]
            [clj-time.core :as time]
            [compojure.core :refer (defroutes routes GET POST ANY)]
            [compojure.handler :refer (site)]
            [compojure.route]
            [org.httpkit.server :as httpkit]
            [pc.datomic :as pcd]
            [pc.http.datomic :as datomic]
            [pc.models.layer :as layer]
            [pc.less :as less]
            [pc.views.content :as content]
            [pc.stefon]
            [stefon.core :as stefon]
            [taoensso.sente :as sente])
  (:import java.util.UUID))

;; TODO: find a way to restart sente
(defonce sente-state (atom {}))

(defn log-request [req resp ms]
  (when-not (re-find #"^/cljs" (:uri req))
    (log/infof "%s: %s %s for %s in %sms" (:status resp) (:request-method req) (:uri req) (:remote-addr req) ms)))

(defn logging-middleware [handler]
  (fn [req]
    (let [start (time/now)
          resp (handler req)
          stop (time/now)]
      (try
        (log-request req resp (time/in-millis (time/interval start stop)))
        (catch Exception e
          (log/error e)))
      resp)))

(defn app [sente-state]
  (routes
   (POST "/api/entity-ids" request
         (datomic/entity-id-request (-> request :body slurp edn/read-string :count)))
   (POST "/api/transact" request
         (datomic/transact! (-> request :body slurp edn/read-string :datoms)))
   (GET "/api/document/:id" [id]
        ;; TODO: should probably verify document exists
        ;; TODO: take tx-date as a param
        {:status 200
         :body (pr-str {:layers (layer/find-by-document (pcd/default-db) {:db/id (Long/parseLong id)})})})
   (GET "/" [] (content/app))
   (compojure.route/resources "/" {:root "public"
                                   :mime-types {:svg "image/svg"}})
   (GET "/chsk" req ((:ajax-get-or-ws-handshake-fn sente-state) req))
   (POST "/chsk" req ((:ajax-post-fn sente-state) req))
   (ANY "*" [] {:status 404 :body nil})))

(defn port []
  (if (System/getenv "HTTP_PORT")
    (Integer/parseInt (System/getenv "HTTP_PORT"))
    8080))

(defn start [sente-state]
  (def server (httpkit/run-server (-> (app sente-state)
                                      (logging-middleware)
                                      (site)
                                      (stefon/asset-pipeline pc.stefon/stefon-options))
                                  {:port (port)})))

(defn stop []
  (server))

(defn restart []
  (stop)
  (start @sente-state))

(defn user-id-fn [ring-req]
  (UUID/randomUUID))

;; hash-map of document-id to set of connected user-ids
;; Used to keep track of which transactions to send to which user
;; sente's channel handling stuff is not much fun to work with :(
(defonce document-subs (atom {}))

(defn ws-handler-dispatch-fn [req]
  (-> req :event first))

(defmulti ws-handler ws-handler-dispatch-fn)

(defmethod ws-handler :default [req]
  (def req req)
  (log/infof "%s for %s" (:event req) (:client-uuid req)))

(defn clean-document-subs [uuid]
  (swap! document-subs (fn [ds]
                         ;; Could be optimized...
                         (reduce (fn [acc [document-id user-ids]]
                                   (if-not (contains? user-ids uuid)
                                     acc
                                     (let [new-user-ids (disj user-ids uuid)]
                                       (if (empty? new-user-ids)
                                         (dissoc acc document-id)
                                         (assoc acc document-id new-user-ids)))))
                                 ds ds))))

(defmethod ws-handler :chsk/uidport-close [{:keys [client-uuid] :as req}]
  (log/infof "closing connection for %s" client-uuid)
  (clean-document-subs client-uuid))

(defn subscribe-to-doc [document-id uuid]
  (swap! document-subs update-in [document-id] (fnil conj #{}) uuid))

(defmethod ws-handler :frontend/subscribe [{:keys [client-uuid ?data] :as req}]
  (let [document-id (-> ?data :document-id)]
    (log/infof "subscribing %s to %s" client-uuid document-id)
    (subscribe-to-doc document-id client-uuid)))

(defn setup-ws-handlers [sente-state]
  (let [tap (async/chan (async/sliding-buffer 100))
        mult (async/mult (:ch-recv sente-state))]
    (async/tap mult tap)
    (async/go-loop []
                   (when-let [req (async/<! tap)]
                     (ws-handler req)
                     (recur)))))

(defn sente-init []
  (let [{:keys [ch-recv send-fn ajax-post-fn connected-uids
                ajax-get-or-ws-handshake-fn] :as fns} (sente/make-channel-socket! {:user-id-fn #'user-id-fn})]
    (reset! sente-state fns)
    (setup-ws-handlers fns)))

(defn init []
  (sente-init)
  (start @sente-state))
