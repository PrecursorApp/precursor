(ns frontend.landing-doc
  "Handles fetching the initial doc for outer pages or
   pages that don't come with a doc built-in."
  (:require [cljs.core.async :as async]
            [datascript :as d]
            [frontend.utils :as utils]
            [frontend.utils.ajax :as ajax])
  (:require-macros [cljs.core.async.macros :as am :refer [go go-loop alt!]]))

(defonce doc-fetched? (atom false))
(defonce doc-ch (async/promise-chan))

(defn lock-doc-fetcher []
  (compare-and-set! doc-fetched? false true))

(defn maybe-fetch-doc [state]
  (when (lock-doc-fetcher)
    (go
      (when-let [doc (or (when (:document/id state) {:db/id (:document/id state)})
                         (let [result (async/<! (ajax/managed-ajax :post "/api/v1/document/new"))]
                           (if (= :success (:status result))
                             (do
                               ;; need it in the db so we can get the name
                               (d/transact! (:db state) [(:document result)])
                               (:document result))
                             ;; something went wrong, notifying error channel
                             (async/put! (get-in state [:comms :errors]) [:api-error result]))))]
        (async/put! doc-ch doc)))))

(defn get-doc [state]
  (go (or (when (utils/inspect (:document/id state)) {:db/id (:document/id state)})
          (async/<! doc-ch))))
