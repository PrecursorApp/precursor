(ns pc.core
  (:require [compojure.core :refer (defroutes GET ANY)]
            [compojure.handler :refer (site)]
            [compojure.route]
            [pc.less :as less]
            [stefon.core :as stefon]
            [org.httpkit.server :as httpkit]))

(def stefon-options
  {:asset-roots ["resources/assets"]
   :mode :development})

(defroutes routes
  (compojure.route/resources "/" {:root "public"
                                  :mime-types {:svg "image/svg"}})
  (ANY "*" [] {:status 404 :body nil}))

(defn -main
  "Starts the server that will serve the assets when visiting circle with ?use-local-assets=true"
  []
  (println "Starting less compiler.")
  (less/init)
  (println "starting server on port 8080")
  (httpkit/run-server (stefon/asset-pipeline (site #'routes) stefon-options) {:port 8080}))
