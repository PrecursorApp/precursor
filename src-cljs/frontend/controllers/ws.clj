(ns frontend.controllers.ws
  (:require [frontend.utils :as utils]))

(defmacro with-swallow-ignored-build-channels
  "Ignores pusher messages from build channels that we're ignoring. n.b. these
  could be channels that we want to remain subscribed to, but are just waiting for
  the build to finish loading."
  [state channel-name & body]
  `(if (ignore-build-channel? ~state ~channel-name)
     (do (utils/mlog "Ignoring event for stale channel: " ~channel-name)
         ~state)
     ~@body))
