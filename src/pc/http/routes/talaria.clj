(ns pc.http.routes.talaria
  (:require [clojure.tools.logging :as log]
            [defpage.core :as defpage :refer (defpage)]
            [immutant.web.async :as immutant]
            [pc.http.talaria :as tal]
            [pc.rollbar :as rollbar]
            [ring.middleware.anti-forgery :as csrf]
            [crypto.equality :as crypto])
  (:import [java.util UUID]))

(defpage "/talaria" [req]
  (if (crypto/eq? (pc.utils/inspect (get-in req [:params :csrf-token]))
                  csrf/*anti-forgery-token*)
    (let [channel-id (UUID/randomUUID)]
      (immutant/as-channel (assoc req :tal/ch-id channel-id)
                           {:on-open (tal/handle-ws-open tal/talaria-state)
                            :on-error (tal/handle-ws-error tal/talaria-state)
                            :on-close (tal/handle-ws-close tal/talaria-state)
                            :on-message (tal/handle-ws-msg tal/talaria-state)}))
    {:status 400
     :body "Invalid CSRF token"}))

(def app (defpage/collect-routes))
