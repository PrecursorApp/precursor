(ns pc.http.handlers.errors
  (:require [clojure.tools.logging :as log]
            [pc.rollbar :as rollbar]
            [slingshot.slingshot :refer (try+ throw+)]))

(defn wrap-errors [handler]
  (fn [req]
    (try+
      (handler req)
      (catch :status t
        (log/error t)
        {:status (:status t)
         :body (:public-message t)})
      (catch Object e
        (let [t (:throwable &throw-context)]
          (log/error t)
          (.printStackTrace t)
          (rollbar/report-exception t :request req :cust (some-> req :auth :cust))
          {:status 500
           :body "Sorry, something completely unexpected happened!"})))))
