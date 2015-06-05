(ns frontend.landing-doc
  "Handles fetching the initial doc for outer pages or
   pages that don't come with a doc built-in."
  (:require [cljs.core.async :as async]
            [cljs.reader :as reader]
            [cljs-http.client :as http]
            [datascript :as d]
            [frontend.utils :as utils])
  (:require-macros [cljs.core.async.macros :as am :refer [go go-loop alt!]]))

(defonce doc-fetched? (atom false))
(defonce doc-ch (async/promise-chan))

(defn lock-doc-fetcher []
  (compare-and-set! doc-fetched? false true))

(defn maybe-fetch-doc [state & {:keys [params]
                                :or {params {}}}]
  (when (lock-doc-fetcher)
    (go
      (when-let [doc (or (when (:document/id state) {:db/id (:document/id state)})
                         (let [result (<! (http/post "/api/v1/document/new" {:edn-params params
                                                                             :headers {"X-CSRF-Token" (utils/csrf-token)}}))]
                           (if (:success result)
                             (let [document (-> result :body reader/read-string :document)]
                               (d/transact! (:db state) [document] {:server-update true})
                               document)
                             (async/put! (get-in state [:comms :errors]) [:api-error result]))))]
        (async/put! doc-ch doc)))))

(defn get-doc [state]
  (go (or (when (:document/id state) {:db/id (:document/id state)})
          (async/<! doc-ch))))
