(ns pc.http.routes.twilio
  (:require [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [defpage.core :as defpage :refer (defpage)]
            [pc.rollbar :as rollbar]))

(defpage status-callback [:post "/hooks/twilio"] [req]
  (let [params (:params req)]
    (if (:ErrorCode params)
      (do
        (log/errorf "message to %s failed: status %s, error-code %s, body: %s"
                    (:To params) (:MessageStatus params) (:ErrorCode params) (:Body params))
        (rollbar/report-error "twilio message failed" (select-keys params [:ErrorCode :Body :From :To
                                                                           :AccountSid :NumMedia :MediaUrl
                                                                           :MessageStatus :MessageSid])))
      (log/infof "message to %s succeeded with %s"
                 (:To params) (:MessageStatus params)))))

(def app (defpage/collect-routes))
