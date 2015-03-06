(ns pc.server
  (:require [clj-time.core :as time]
            [clj-time.format :as time-format]
            [clojure.tools.logging :as log]
            [compojure.core]
            [compojure.route]
            [datomic.api :refer [db q] :as d]
            [org.httpkit.server :as httpkit]
            [pc.datomic :as pcd]
            [pc.http.datomic :as datomic]
            [pc.http.handlers.errors :as errors-handler]
            [pc.http.handlers.logging :as logging-handler]
            [pc.http.handlers.ssl :as ssl-handler]
            [pc.http.routes.api :as api]
            [pc.http.routes :as routes]
            [pc.http.routes.blog :as blog]
            [pc.http.sente :as sente]
            [pc.models.access-grant :as access-grant-model]
            [pc.models.cust :as cust-model]
            [pc.models.permission :as permission-model]
            [pc.profile :as profile]
            [pc.rollbar :as rollbar]
            [ring.middleware.anti-forgery :refer (wrap-anti-forgery)]
            [ring.middleware.params :refer (wrap-params)]
            [ring.middleware.keyword-params :refer (wrap-keyword-params)]
            [ring.middleware.reload :refer (wrap-reload)]
            [ring.middleware.session.cookie :refer (cookie-store)]
            [ring.middleware.session :refer (wrap-session)])
  (:import java.util.UUID))

(defn auth-middleware [handler]
  (fn [req]
    (let [db (pcd/default-db)
          cust (some->> req :session :http-session-key (cust-model/find-by-http-session-key db))
          access-grant (some->> req :params :access-grant-token (access-grant-model/find-by-token db))
          permission (some->> req :params :auth-token (permission-model/find-by-token db))]
      (when (and cust access-grant)
        (permission-model/convert-access-grant access-grant cust {:document/id (:access-grant/document access-grant)
                                                                  :transaction/document (:access-grant/document access-grant)
                                                                  :cust/uuid (:cust/uuid cust)
                                                                  :transaction/broadcast true}))
      (handler (-> (cond cust (assoc-in req [:auth :cust] cust)
                         access-grant (assoc-in req [:auth :access-grant] access-grant)
                         :else req)
                 (assoc-in [:auth :permission] permission))))))

(defn wrap-wrap-reload
  "Only applies wrap-reload middleware in development"
  [handler]
  (if (profile/prod?)
    handler
    (wrap-reload handler)))

(defn assoc-sente-id [req response sente-id]
  (if (= (get-in req [:session :sente-id]) sente-id)
    response
    (-> response
      (assoc :session (:session response (:session req)))
      (assoc-in [:session :sente-id] sente-id))))

(defn wrap-sente-id [handler]
  (fn [req]
    (let [sente-id (or (get-in req [:session :sente-id])
                       (str (UUID/randomUUID)))]
      (if-let [response (handler (assoc-in req [:session :sente-id] sente-id))]
        (assoc-sente-id req response sente-id)))))

(defn catch-all [req]
  {:status 404
   :body "<body>Sorry, we couldn't find that page. <a href='/'>Back to home</a>.</body>"})

(defn handler [sente-state]
  (-> (compojure.core/routes #'routes/app
                             #'api/app
                             #'blog/app
                             (compojure.route/resources "/" {:root "public"
                                                             :mime-types {:svg "image/svg"}})
                             #'catch-all)
    (auth-middleware)
    (wrap-anti-forgery)
    (wrap-sente-id)
    (wrap-keyword-params)
    (wrap-params)
    (wrap-session {:store (cookie-store {:key (profile/http-session-key)})
                   :cookie-attrs {:http-only true
                                  :expires (time-format/unparse (:rfc822 time-format/formatters) (time/from-now (time/years 1))) ;; expire one year after the server starts up
                                  :max-age (* 60 60 24 365)
                                  :secure (profile/force-ssl?)}})
    (ssl-handler/wrap-force-ssl {:host (profile/hostname)
                                 :https-port (profile/https-port)
                                 :force-ssl? (profile/force-ssl?)})
    (wrap-wrap-reload)
    (errors-handler/wrap-errors)
    (logging-handler/wrap-logging)))

(defn start [sente-state]
  (def server (httpkit/run-server (handler sente-state)
                                  {:port (profile/http-port)})))

(defn stop [& {:keys [timeout]
               :or {timeout 0}}]
  (server :timeout timeout))

(defn restart []
  (stop)
  (start @sente/sente-state))

(defn init []
  (let [sente-state (sente/init)]
    (start sente-state))
  (datomic/init))

(defn shutdown []
  (sente/shutdown :sleep-ms 250)
  (stop :timeout 1000))
