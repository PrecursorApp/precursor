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
  (if (crypto/eq? (get-in req [:params "csrf-token"])
                  csrf/*anti-forgery-token*)
    (if (:websocket? req)
      (let [channel-id (str (get-in req [:session :sente-id])
                            "-"
                            (get-in req [:params "tab-id"]))]
        (immutant/as-channel (assoc req :tal/ch-id channel-id)
                             {:on-open (tal/ws-open-handler tal/talaria-state)
                              :on-close (tal/ws-close-handler tal/talaria-state)
                              :on-message (tal/ws-msg-handler tal/talaria-state)
                              :on-error (tal/ws-error-handler tal/talaria-state)}))
      {:status 400 :body "WebSocket headers not present"})
    {:status 400
     :body "Invalid CSRF token"}))

(defpage "/talaria/ajax-poll" [req]
  (let [channel-id (str (get-in req [:session :sente-id])
                        "-"
                        (get-in req [:params "tab-id"]))]
    (if (get-in req [:params "open?"])
      (do (tal/handle-ajax-open tal/talaria-state channel-id req)
          {:status 200 :body "connected"})
      (immutant/as-channel (assoc req :tal/ch-id channel-id)
                           {:on-open (tal/ajax-channel-handler tal/talaria-state channel-id)
                            :on-close (tal/ajax-close-handler tal/talaria-state)
                            :on-error (tal/ws-error-handler tal/talaria-state)}))))

(defpage [:post "/talaria/ajax-send"] [req]
  (let [channel-id (str (get-in req [:session :sente-id])
                        "-"
                        (get-in req [:params "tab-id"]))
        res (tal/handle-ajax-msg tal/talaria-state channel-id (:body req) req)]
    (case res
      :sent {:status 200 :body ""}
      :channel-closed {:status 400 :body "channel-closed"})))

(def app (defpage/collect-routes))
