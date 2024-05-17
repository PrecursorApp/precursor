(ns pc.init
  (:require pc.assets
            pc.billing
            pc.cache
            pc.datomic
            pc.datomic.admin-db
            pc.datomic.migrations
            pc.datomic.schema
            pc.email
            pc.gauges
            pc.http.admin
            pc.http.webhooks
            pc.less
            pc.logging
            pc.models.chat-bot
            pc.models.plan
            pc.nrepl
            pc.nts
            pc.profile
            pc.repl
            pc.server
            pc.slack
            pc.statsd
            pc.stripe
            pc.stripe.dev
            pc.utils)
  (:gen-class))

(defn init-fns []
  [#'pc.nrepl/init
   #'pc.profile/init
   #'pc.logging/init
   ;; #'pc.statsd/init
   #'pc.datomic/init
   #'pc.datomic.schema/init
   #'pc.datomic.migrations/init
   #'pc.models.chat-bot/init
   #'pc.models.plan/init
   ;;#'pc.nts/init
   #'pc.assets/init
   #'pc.email/init
   #'pc.cache/init
   #'pc.stripe/init
   #'pc.slack/init
   #'pc.server/init
   #'pc.less/init
   #'pc.http.webhooks/init
   #'pc.datomic.admin-db/init
   #'pc.http.admin/init
   #'pc.billing/init
   #'pc.stripe.dev/init
   #'pc.gauges/init])

(defn pretty-now []
  (.toLocaleString (java.util.Date.)))

(defn init []
  (doseq [f (init-fns)]
    (println (pretty-now) f)
    (f)))

(defn shutdown []
  (pc.utils/shutdown-safe-scheduled-jobs)
  (pc.server/shutdown)
  (pc.datomic/shutdown)
  (pc.http.webhooks/shutdown)
  (pc.http.admin/shutdown))

(defn -main []
  (init)
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. (fn []
                               (println (pretty-now) "shutting down")
                               (shutdown))))
  (println (pretty-now) "done"))
