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
            [taoensso.sente :as sente]))

;; TODO: find a way to restart senta
(defonce senta-state (atom {}))

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

(defn app [senta-state]
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
   (GET "/chsk" req ((:ajax-get-or-ws-handshake-fn senta-state) req))
   (POST "/chsk" req ((:ajax-post-fn senta-state) req))
   (ANY "*" [] {:status 404 :body nil})))

(defn port []
  (if (System/getenv "HTTP_PORT")
    (Integer/parseInt (System/getenv "HTTP_PORT"))
    8080))

(defn start [senta-state]
  (def server (httpkit/run-server (-> (app senta-state)
                                      (logging-middleware)
                                      (site)
                                      (stefon/asset-pipeline pc.stefon/stefon-options))
                                  {:port (port)})))

(defn stop []
  (server))

(defn restart []
  (stop)
  (start @senta-state))

(defn user-id-fn [ring-req]
  (println "calling user-id-fn")
  (println (get-in ring-req [:params :test]))
  (get-in ring-req [:params :test]))

(defn ws-handler [req]
  (def req req)
  (log/info (:event req)))

(defn setup-ws-handlers [senta-state]
  (let [tap (async/chan (async/sliding-buffer 100))
        mult (async/mult (:ch-recv senta-state))]
    (async/tap mult tap)
    (async/go-loop []
                   (when-let [req (async/<! tap)]
                     (ws-handler req)
                     (recur)))))

(defn senta-init []
  (let [{:keys [ch-recv send-fn ajax-post-fn connected-uids
                ajax-get-or-ws-handshake-fn] :as fns} (sente/make-channel-socket! {:user-id-fn #'user-id-fn})]
    (reset! senta-state fns)
    (setup-ws-handlers fns)))

(defn init []
  (senta-init)
  (start @senta-state))
