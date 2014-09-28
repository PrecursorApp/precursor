(ns pc.server
  (:require [compojure.core :refer (defroutes GET POST ANY)]
            [compojure.handler :refer (site)]
            [compojure.route]
            [clojure.tools.reader.edn :as edn]
            [pc.http.datomic :as datomic]
            [pc.less :as less]
            [pc.views.content :as content]
            [pc.stefon]
            [stefon.core :as stefon]
            [org.httpkit.server :as httpkit]))

(defroutes routes
  (POST "/api/entity-ids" request
        (datomic/entity-id-request (-> request :body slurp edn/read-string :count)))
  (POST "/api/transact" request
        (datomic/transact! (-> request :body slurp edn/read-string :datoms)))
  (GET "/" [] (content/app))
  (compojure.route/resources "/" {:root "public"
                                  :mime-types {:svg "image/svg"}})
  (ANY "*" [] {:status 404 :body nil}))

(defn port []
  (if (System/getenv "HTTP_PORT")
    (Integer/parseInt (System/getenv "HTTP_PORT"))
    8080))

(defn start []
  (def server (httpkit/run-server (-> (site #'routes)
                                      (stefon/asset-pipeline pc.stefon/stefon-options)
                                      )
                                  {:port (port)})))

(defn stop []
  (server))

(defn restart []
  (stop)
  (start))

(defn init []
  (start))
