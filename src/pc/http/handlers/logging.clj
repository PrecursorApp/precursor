(ns pc.http.handlers.logging
  (:require [clj-statsd :as statsd]
            [clj-time.core :as time]
            [clojure.tools.logging :as log]
            [pc.rollbar :as rollbar]))

(defn log-request [req resp ms]
  (when-not (or (re-find #"^/cljs" (:uri req))
                (and (= 200 (:status resp))
                     (= "/health-check" (:uri req))))
    (let [cust (-> req :auth :cust)
          cust-str (when cust (str (:db/id cust) " (" (:cust/email cust) ")"))]
      ;; log haproxy status if health check is down
      (when (= "/health-check" (:uri req))
        (log/info (:headers req)))
      (log/infof "%s: %s %s %s for %s %s in %sms" (:status resp) (:request-method req) (:server-name req) (:uri req) (:remote-addr req) cust-str ms))))

(defn wrap-logging [handler]
  (fn [req]
    (statsd/with-timing :http-request
      (let [start (time/now)
            resp (handler req)
            stop (time/now)]
        (try
          (log-request req resp (time/in-millis (time/interval start stop)))
          (catch Exception e
            (rollbar/report-exception e :request req :cust (get-in req [:auth :cust]))
            (log/error e)))
        resp))))
