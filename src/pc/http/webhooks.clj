(ns pc.http.webhooks
  (:require [compojure.core]
            [immutant.web :as web]
            [pc.http.handlers.errors :as errors-handler]
            [pc.http.handlers.logging :as logging-handler]
            [pc.http.handlers.ssl :as ssl-handler]
            [pc.http.routes.twilio :as twilio]
            [pc.profile :as profile]
            [ring.middleware.keyword-params :refer (wrap-keyword-params)]
            [ring.middleware.params :refer (wrap-params)]
            [ring.middleware.reload :refer (wrap-reload)]))

(defn wrap-wrap-reload
  "Only applies wrap-reload middleware in development"
  [handler]
  (if (profile/prod?)
    handler
    (wrap-reload handler)))

(defn catch-all [req]
  {:status 404
   :body "<body>Sorry, we couldn't find that page. <a href='/'>Back to home</a>.</body>"})

(defn handler []
  (-> (compojure.core/routes #'twilio/hooks-app
                             #'catch-all)
    (wrap-keyword-params)
    (wrap-params)
    (ssl-handler/wrap-force-ssl {:host (profile/hostname)
                                 :https-port (profile/https-port)
                                 :force-ssl? (profile/force-ssl?)})
    (wrap-wrap-reload)
    (errors-handler/wrap-errors)
    (logging-handler/wrap-logging {:context "webhooks"})))

(defn start []
  (def server (web/server (web/run
                            (handler)
                            {:port (profile/http-port)
                             :host "0.0.0.0"
                             :path "/hooks"}))))

(defn stop []
  (.stop server))

(defn restart []
  (stop)
  (start))

(defn init []
  (start))

(defn shutdown []
  (stop))
