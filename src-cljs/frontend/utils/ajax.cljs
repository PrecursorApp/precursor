(ns frontend.utils.ajax
  (:require [ajax.core :as clj-ajax]
            [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [cljs-time.core :as time]
            [clojure.string :as str]
            [frontend.async :refer [put!]]
            [frontend.utils :as utils :include-macros true]))

;; https://github.com/JulianBirch/cljs-ajax/blob/master/src/ajax/core.cljs
;; copy of the default json formatter, but returns a map with json body
;; in :resp and extra request metadata: :response-headers, :url, :method, and :request-time
(defn json-response-format
  "Returns a JSON response format.  Options include
   :keywords? Returns the keys as keywords
   :prefix A prefix that needs to be stripped off.  This is to
   combat JSON hijacking.  If you're using JSON with GET request,
   you should use this.
   http://stackoverflow.com/questions/2669690/why-does-google-prepend-while1-to-their-json-responses
   http://haacked.com/archive/2009/06/24/json-hijacking.aspx"
  ([{:keys [prefix keywords? url method start-time]
     :or {start-time (time/now)}}]
     {:read (fn read-json [xhrio]
              (let [json (js/JSON.parse (.getResponseText xhrio))
                    headers (js->clj (.getResponseHeaders xhrio))
                    request-time (try
                                   (time/in-millis (time/interval start-time (time/now)))
                                   (catch :default e
                                     (utils/merror e)
                                     0))]
                {:resp (js->clj json :keywordize-keys keywords?)
                 :response-headers headers
                 :url url
                 :method method
                 :request-time request-time}))
      :description (str "JSON"
                        (if prefix (str " prefix '" prefix "'"))
                        (if keywords? " keywordize"))}))

(defn xml-request-response []
  {:read (fn read-xml [xhrio]
           (set! js/window.testx (.getResponseXml xhrio))
           {:resp (.getResponseXml xhrio)})
   :description "XML"
   :content-type "application/xml"
   :write identity})

(defn normalize-error-response [default-response props]
  (-> default-response
      (merge props)
      (assoc :status-code (:status default-response))
      (assoc :resp (get-in default-response [:response :resp]))
      (assoc :status :failed)))

;; TODO only implementing JSON/RAW format and not implementing prefixes for now since we don't anything else
(defn ajax [method url message channel & {:keys [params keywords? context headers format body]
                                          :or {keywords? true format :edn}}]
  (let [uuid frontend.async/*uuid*
        format-opts (case format
                      :json {:format (merge (clj-ajax/json-request-format)
                                            (json-response-format {:keywords? keywords? :url url :method method}))
                             :response-format :json
                             :keywords? keywords?
                             :params params
                             :headers (merge {:Accept "application/json"}
                                             (when (re-find #"^/" url)
                                               {:X-CSRFToken (utils/csrf-token)})
                                             headers)
                             :handler #(binding [frontend.async/*uuid* uuid]
                                         (put! channel [message :success (assoc % :context context)]))
                             :error-handler #(binding [frontend.async/*uuid* uuid]
                                               (put! channel [message :failed (normalize-error-response % {:url url :context context})]))}
                      :edn {:format (clj-ajax/edn-format)
                            :params params
                            :headers (merge {:Accept "application/edn"}
                                            (when (re-find #"^/" url)
                                              {:X-CSRF-Token (utils/csrf-token)})
                                            headers)
                            :handler #(binding [frontend.async/*uuid* uuid]
                                        (put! channel [message :success {:resp % :context context}]))
                            :error-handler #(binding [frontend.async/*uuid* uuid]
                                              (put! channel [message :failed (normalize-error-response % {:url url :context context})]))}

                      ;; TODO: use a custom reader or similar for raw to handle more like json
                      :raw {:format (clj-ajax/raw-format)
                            :handler #(binding [frontend.async/*uuid* uuid]
                                        (put! channel [message :success {:resp % :context context}]))
                            :error-handler #(binding [frontend.async/*uuid* uuid]
                                              (put! channel [message :failed {:resp % :url url :context context :status :failed}]))})]
    (clj-ajax/ajax-request url method
                           (clj-ajax/transform-opts format-opts))))

;; This is all very mess, it should be cleaned up at some point
(defn managed-ajax [method url & {:keys [params keywords? headers format]
                                  :or {keywords? true
                                       format :edn}}]
  (let [channel (chan)
        opt-format (get {:xml (xml-request-response)
                         :json (merge (clj-ajax/json-request-format)
                                      (json-response-format {:keywords? keywords? :url url :method method}))
                         :edn (clj-ajax/edn-format)}
                        format)
        accept-header (utils/inspect (get {:xml "application/xml"
                                           :json "application/json"
                                           :edn "application/edn"}
                                          (utils/inspect format)))]
    (clj-ajax/ajax-request url method
                           (clj-ajax/transform-opts
                            {:format opt-format
                             :response-format opt-format
                             :keywords? keywords?
                             :params params
                             :headers (merge (when accept-header
                                               {:Accept accept-header})
                                             (when (re-find #"^/" url)
                                               {:X-CSRF-Token (utils/csrf-token)})
                                             headers)
                             :handler #(put! channel (assoc % :status :success))
                             ;; TODO: clean this up
                             :error-handler #(put! channel (normalize-error-response % {:url url}))
                             :finally #(close! channel)}))
    channel))


;; TODO this should be possible to do with the normal ajax function, but punting for now
(defn managed-form-post [url & {:keys [params headers keywords?]
                                :or {keywords? true}}]
  (let [channel (chan)]
    (clj-ajax/ajax-request url :post
                           (clj-ajax/transform-opts
                            {:format (merge (clj-ajax/url-request-format)
                                            (json-response-format {:keywords? keywords? :url url :method :post}))
                             :response-format :json
                             :params params
                             :headers (merge {:Accept "application/json"}
                                             (when (re-find #"^/" url)
                                               {:X-CSRFToken (utils/csrf-token)})
                                             headers)
                             :handler #(put! channel (assoc % :status :success))
                             :error-handler #(put! channel %)
                             :finally #(close! channel)}))
    channel))
