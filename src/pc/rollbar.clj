(ns pc.rollbar
  (:require [clojure.string :as str]
            [clj-http.client :as clj-http]
            [clj-stacktrace.core :refer [parse-exception]]
            [clj-stacktrace.repl :refer [method-str]]
            [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [pc.profile :as profile]))

;; copied from https://github.com/rollbar/clj-rollbar, but
;; uses async http requests and a faster json formatter

(def endpoint
  "Rollbar API endpoint base URL"
  "https://api.rollbar.com/api/1/item/")

(def rollbar-prod-token "cca6d7468e71428fb656f573e5012eaf")
(def rollbar-dev-token "cac8b256153f4485938751ec3731c9e2")
(defn token []
  (if (profile/prod?)
    rollbar-prod-token
    rollbar-dev-token))

(def rollbar-prod-client-token "744b16251ecf48d285e4f5e9470bf32f")
(def rollbar-dev-client-token "2eaa2b1406d945edaddad08619bbe1ac")
(defn rollbar-client-token []
  (if (profile/prod?)
    rollbar-prod-client-token
    rollbar-dev-client-token))

(defn build-payload
  [access-token data-maps]
  (json/encode {:access_token access-token
                :data (apply merge data-maps)}))

(defonce rollbar-agent (agent nil
                              :error-mode :continue
                              :error-handler (fn [a e]
                                               (when (profile/prod?)
                                                 (log/error "Error in rollbar agent" e)
                                                 (.printStackTrace e)))))

(defn send-payload* [_ payload]
  (clj-http/post endpoint
                 {:body payload
                  :content-type :json
                  :socket-timeout 30000 ; 30s
                  :conn-timeout 30000   ; 30s
                  :accept :json}))

(defn send-payload
  [payload]
  (send-off rollbar-agent send-payload* payload))

(defn base-data
  [environment level]
  {
    :environment environment
    :level level
    :timestamp (int (/ (System/currentTimeMillis) 1000))
    :language "clojure"
    :notifier {
      :name "clj-rollbar"
      :version "0.0.3"
    }
    ; TODO uuid
  })

(defn url-for
  [{:keys [scheme server-name server-port uri] :as request}]
  (format "%s://%s:%s%s" (name scheme) server-name server-port uri))

(defn to-http-case
  [s]
  (str/replace (str/capitalize s) #"-(.)"
               (fn [[_ char]] (str "-" (str/capitalize char)))))

(defn request-data
  [{:keys [request-method remote-addr query-string] :as request} cust]
  (merge {:request {:url (url-for request)
                    :method (str/upper-case (name request-method))
                    :query_string (or query-string "")
                    :user_ip remote-addr}}
         (when (seq cust)
           {:person {:id (str (:cust/uuid cust))
                     :email (:cust/email cust)}})))

(defn report-message
  "Reports a simple string message at the specified level"
  [access-token environment message-body level]
  (send-payload
    (build-payload access-token
                   [(base-data environment level)
                    {:body {:message {:body message-body}}}])))

(def ^:private shim-name "shim-frame.clj")

(defn get-shim-frames
  [parsed-exception]
  [ { :lineno 0
      :filename shim-name
      :method "causedBy"
    }
    { :lineno 0
      :filename shim-name
      :method (str (:class parsed-exception))
    }])

(defn project-frame
  [stackframe]
  (let [{:keys [file line], :as elem} stackframe]
    {
      :lineno line
      :filename file
      :method (method-str elem)
    }))

(defn project-exception-to-frames
  [parsed-exception]
  (vec (map project-frame (:trace-elems parsed-exception))))

(defn build-frames-with-causes
  [parsed-exception]
  (loop [cur-exception parsed-exception
         frames []]
    (if (:cause cur-exception)
      (recur (:cause parsed-exception)
             (concat frames (project-exception-to-frames cur-exception) (get-shim-frames parsed-exception)))
      (concat frames (project-exception-to-frames cur-exception)))))

(defn build-trace
  [parsed-exception]
  {
    :exception {
      :class (first (str/split (str (get parsed-exception :class)) #":"))
      :message (get parsed-exception :message)
    }
    :frames (build-frames-with-causes parsed-exception)
  })

(defn report-exception
  "Reports an exception at the 'error' level"
  [exception & {:keys [request cust]}]
  (let [data-map (base-data (profile/env) "error")
        trace-map {:body {:trace (build-trace
                                  (parse-exception exception))}}
        request-map (if request (request-data request cust) {})
        data-maps [data-map trace-map request-map]]
    (send-payload (build-payload (token) data-maps))))

(defn report-error
  "Reports an exception at the 'error' level"
  [error-string & [params]]
  (let [data-map (base-data (profile/env) "error")
        request-map (when (:request params)
                      (request-data (:request params) (:cust params)))]
    (send-payload (build-payload (token) [data-map
                                          request-map
                                          (-> params
                                            (assoc :body {:message {:body error-string}})
                                            (dissoc :request :cust))]))))
