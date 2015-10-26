(ns pc.nrepl
  (:require [clojure.tools.nrepl.server :refer (start-server stop-server)]
            [cider.nrepl]))

(defn port []
  (if (System/getenv "NREPL_PORT")
    (Integer/parseInt (System/getenv "NREPL_PORT"))
    6005))

(defn init []
  (let [port (port)]
    (println "Starting nrepl on port" port)
    (def server (start-server :port port :handler cider.nrepl/cider-nrepl-handler))))
