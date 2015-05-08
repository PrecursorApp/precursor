(ns frontend.landing-doc
  "Handles fetching the initial doc for outer pages or
   pages that don't come with a doc built-in."
  (:require [cljs.core.async :as async]
            [frontend.utils :as utils]
            [frontend.utils.ajax :as ajax])
  (:require-macros [cljs.core.async.macros :as am :refer [go go-loop alt!]]))

(defonce doc-id-fetched? (atom false))
(defonce doc-id-ch (async/promise-chan))

(defn lock-doc-id-fetcher []
  (compare-and-set! doc-id-fetched? false true))

(defn maybe-fetch-doc-id [state]
  (when (lock-doc-id-fetcher)
    (go
      (when-let [doc-id (or (:document/id state)
                            (let [result (async/<! (ajax/managed-ajax :post "/api/v1/document/new"))]
                              (if (= :success (:status result))
                                (get-in result [:document :db/id])
                                ;; something went wrong, notifying error channel
                                (async/put! (get-in state [:comms :errors]) [:api-error result]))))]
        (async/put! doc-id-ch doc-id)))))
