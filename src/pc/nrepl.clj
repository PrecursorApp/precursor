(ns pc.nrepl
  (:require [clojure.tools.nrepl.server :refer (start-server stop-server default-handler)]
            [cider.nrepl]))

(defn port []
  (if (System/getenv "NREPL_PORT")
    (Integer/parseInt (System/getenv "NREPL_PORT"))
    6005))

;; see cider.nrepl/cider-nrepl-handler
(defn cider-middleware [] (map resolve cider.nrepl/cider-middleware))

(defn init []
  (let [port (port)]
    (println "Starting nrepl on port" port)
    (def server (start-server :port port :handler (apply default-handler (cider-middleware))))))
