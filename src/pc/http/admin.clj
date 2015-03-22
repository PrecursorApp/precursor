(ns pc.http.admin
  (:require [clj-time.core :as time]
            [clj-time.format :as time-format]
            [compojure.core]
            [compojure.route]
            [immutant.web :as web]
            [ns-tracker.core :refer (ns-tracker)]
            [pc.datomic.admin-db :as admin-db]
            [pc.http.admin.auth :as auth]
            [pc.http.admin.inner :as inner]
            [pc.http.admin.outer :as outer]
            [pc.http.handlers.errors :as errors-handler]
            [pc.http.handlers.logging :as logging-handler]
            [pc.http.handlers.ssl :as ssl-handler]
            [pc.models.admin :as admin-model]
            [pc.profile :as profile]
            [ring.middleware.anti-forgery :refer (wrap-anti-forgery)]
            [ring.middleware.params :refer (wrap-params)]
            [ring.middleware.reload :refer (wrap-reload)]
            [ring.middleware.session :refer (wrap-session)]
            [ring.middleware.session.cookie :refer (cookie-store)]))

(defn wrap-wrap-reload
  "Only applies wrap-reload middleware in development"
  [handler]
  (if (profile/prod?)
    handler
    (wrap-reload handler)))

(defn catch-all [req]
  {:status 404
   :body "Not found."})

(defn handler []
  (-> (compojure.core/routes #'outer/app
                             #'inner/app
                             (compojure.route/resources "/" {:root "public"
                                                             :mime-types {:svg "image/svg"}})
                             #'catch-all)
    (auth/wrap-auth)
    (wrap-anti-forgery)
    (wrap-params)
    (wrap-session {:store (cookie-store {:key (profile/admin-http-session-key)})
                   :cookie-attrs {:http-only true
                                  ;; expire one year after the server starts up
                                  :expires (time-format/unparse (:rfc822 time-format/formatters) (time/from-now (time/days 1)))
                                  :max-age (* 60 60 24 365)
                                  :secure (profile/force-ssl?)}})
    (ssl-handler/wrap-force-ssl {:host (profile/admin-hostname)
                                 :https-port (profile/admin-https-port)
                                 :force-ssl? (profile/force-ssl?)})
    (wrap-wrap-reload)
    (errors-handler/wrap-errors)
    (logging-handler/wrap-logging)))

(defn start []
  (def server (web/server (web/run
                            (handler)
                            {:port (profile/admin-http-port)}))))

(defn stop [& {:keys [timeout]
               :or {timeout 0}}]
  (.stop server))

(defn restart []
  (stop)
  (start))

(defn init []
  (start))

(defn shutdown []
  (stop :timeout 1000))
