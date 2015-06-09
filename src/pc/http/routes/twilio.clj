(ns pc.http.routes.twilio
  (:require [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [defpage.core :as defpage :refer (defpage)]
            [pc.rollbar :as rollbar]))

;; Note: routes in this namespace have /hooks prepended to them by default
;;       We'll handle this with convention for now, but probably want to
;;       modify clout to use :uri instead of :path-info
;;       https://github.com/weavejester/clout/blob/master/src/clout/core.clj#L35

;; /hooks/twilio
(defpage status-callback [:post "/twilio"] [req]
  (let [params (:params req)]
    (if (:ErrorCode params)
      (do
        (log/errorf "message to %s failed: status %s, error-code %s, body: %s"
                    (:To params) (:MessageStatus params) (:ErrorCode params) (:Body params))
        (rollbar/report-error "twilio message failed" (select-keys params [:ErrorCode :Body :From :To
                                                                           :AccountSid :NumMedia :MediaUrl
                                                                           :MessageStatus :MessageSid])))
      (log/infof "message to %s succeeded with %s"
                 (:To params) (:MessageStatus params)))
    {:status 200
     :headers {"Content-Type" "text/plain"}
     :body ""}))

(def hooks-app (defpage/collect-routes))
