(ns pc.rollbar
  (:require [clojure.string :as str]
            [org.httpkit.client :as http]
            [clj-stacktrace.core :refer [parse-exception]]
            [clj-stacktrace.repl :refer [method-str]]
            [cheshire.core :as json]
            [pc.profile :as profile]))

;; copied from https://github.com/rollbar/clj-rollbar, but
;; uses async http requests and a faster json formatter

(def endpoint
  "Rollbar API endpoint base URL"
  "https://api.rollbar.com/api/1/item/")

(def token "cca6d7468e71428fb656f573e5012eaf")

(defn build-payload
  [access-token data-maps]
  (json/encode {:access_token access-token
                :data (apply merge data-maps)}))

(defn send-payload
  [payload]
  (http/post endpoint
             {:body payload
              :content-type :json
              :socket-timeout 30000     ; 30s
              :conn-timeout 30000       ; 30s
              :accept :json}))

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

(defn headers-for
  [{:keys [headers] :as request}]
  (into {} (for [[k v] headers]
             [(to-http-case k) v])))

(defn request-data
  [{method :request-method client :remote-addr qstr :query-string body :body
    :as request} user-id]
  (let [body (if (string? body)
               body
               (try (slurp body) (catch Throwable e "")))
        rollbar-obj {:url (url-for request)
                     :method (str/upper-case (name method))
                     :headers (headers-for request)
                     :query_string (or qstr "")
                     :user_ip client
                     :body body}
        rollbar-obj (if user-id
                      (assoc rollbar-obj :person {:id (str user-id)})
                      rollbar-obj)]
    {:request rollbar-obj}))

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
  [exception & {:keys [request user-id]}]
  (let [data-map (base-data (profile/env) "error")
        trace-map {:body {:trace (build-trace
                                  (parse-exception exception))}}
        request-map (if request (request-data request user-id) {})
        data-maps [data-map trace-map request-map]]
    (send-payload (build-payload token data-maps))))
