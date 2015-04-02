(ns pc.init
  (:require pc.assets
            pc.cache
            pc.datomic
            pc.datomic.admin-db
            pc.datomic.migrations
            pc.datomic.schema
            pc.email
            pc.http.admin
            pc.less
            pc.logging
            pc.models.chat-bot
            pc.nrepl
            pc.repl
            pc.server
            pc.statsd)
  (:gen-class))

(defn init-fns []
  [#'pc.logging/init
   #'pc.nrepl/init
   #'pc.statsd/init
   #'pc.less/init
   #'pc.datomic/init
   #'pc.datomic.schema/init
   #'pc.datomic.migrations/init
   #'pc.models.chat-bot/init
   #'pc.assets/init
   #'pc.email/init
   #'pc.cache/init
   #'pc.server/init
   #'pc.datomic.admin-db/init
   #'pc.http.admin/init])

(defn pretty-now []
  (.toLocaleString (java.util.Date.)))

(defn init []
  (doseq [f (init-fns)]
    (println (pretty-now) f)
    (f)))

(defn -main []
  (init)
  (println (pretty-now) "done"))

(defn shutdown []
  (pc.server/shutdown)
  (pc.datomic/shutdown))
