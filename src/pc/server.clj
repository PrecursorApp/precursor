(ns pc.server
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [clojure.tools.reader.edn :as edn]
            [clj-time.core :as time]
            [compojure.core :refer (defroutes routes GET POST ANY)]
            [compojure.handler :refer (site)]
            [compojure.route]
            [datomic.api :refer [db q] :as d]
            [org.httpkit.server :as httpkit]
            [pc.datomic :as pcd]
            [pc.http.datomic :as datomic]
            [pc.http.sente :as sente]
            [pc.models.layer :as layer]
            [pc.less :as less]
            [pc.views.content :as content]
            [pc.stefon]
            [ring.middleware.session :refer (wrap-session)]
            [ring.middleware.session.cookie :refer (cookie-store)]
            [ring.util.response :refer (redirect)]
            [stefon.core :as stefon]))

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
         (let [body (-> request :body slurp edn/read-string)]
           (datomic/transact! (:datoms body) (:document-id body) (get-in request [:session :uid]))))
   (GET "/api/document/:id" [id]
        ;; TODO: should probably verify document exists
        ;; TODO: take tx-date as a param
        {:status 200
         :body (pr-str {:layers (layer/find-by-document (pcd/default-db) {:db/id (Long/parseLong id)})})})
   (GET "/document/:document-id" [document-id]
        (content/app))
   (GET "/" []
        (let [[document-id] (pcd/generate-eids (pcd/conn) 1)]
          @(d/transact (pcd/conn) [{:db/id document-id :document/name "Untitled"}])
          (redirect (str "/document/" document-id))))
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
                                      (sente/wrap-user-id)
                                      (wrap-session {:store (cookie-store)})
                                      (logging-middleware)
                                      (site)
                                      (stefon/asset-pipeline pc.stefon/stefon-options))
                                  {:port (port)})))

(defn stop []
  (server))

(defn restart []
  (stop)
  (start @sente/sente-state))


(defn init []
  (let [sente-state (sente/init)]
    (start sente-state))
  (datomic/init))
