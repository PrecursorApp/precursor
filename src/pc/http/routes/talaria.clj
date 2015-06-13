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
    (let [channel-id (str (get-in req [:session :sente-id])
                          "-"
                          (get-in req [:params :tab-id]))]
      (immutant/as-channel (assoc req :tal/ch-id channel-id)
                           {:on-open (tal/handle-ws-open tal/talaria-state)
                            :on-error (tal/handle-ws-error tal/talaria-state)
                            :on-close (tal/handle-ws-close tal/talaria-state)
                            :on-message (tal/handle-ws-msg tal/talaria-state)}))
    {:status 400
     :body "Invalid CSRF token"}))

(defpage "/talaria/ajax-poll" [req]
  (let [channel-id (str (get-in req [:session :sente-id])
                        "-"
                        (get-in req [:params :tab-id]))]
    (if (get-in req [:params :open?])
      (do (tal/handle-ajax-open tal/talaria-state channel-id req)
          {:status 200 :body "connected"})
      (immutant/as-channel (assoc req :tal/ch-id channel-id)
                           {:on-open (tal/handle-ajax-channel tal/talaria-state channel-id)
                            :on-error (tal/handle-ws-error tal/talaria-state)
                            :on-close (tal/handle-ajax-close tal/talaria-state)}))))

(defpage [:post "/talaria/ajax-send"] [req]
  (let [channel-id (str (get-in req [:session :sente-id])
                        "-"
                        (get-in req [:params :tab-id]))]
    (tal/handle-ajax-msg tal/talaria-state channel-id (:body req) req)
    {:status 200 :body ""}))

(def app (defpage/collect-routes))
