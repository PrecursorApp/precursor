(ns pc.http.routes.api
  (:require [clojure.tools.reader.edn :as edn]
            [defpage.core :as defpage :refer (defpage)]
            [pc.early-access]
            [pc.models.doc :as doc-model]
            [pc.models.chat-bot :as chat-bot-model]))

(defpage new [:post "/api/v1/document/new"] [req]
  (let [cust-uuid (get-in req [:auth :cust :cust/uuid])
        doc (doc-model/create-public-doc!
             (merge {:document/chat-bot (rand-nth chat-bot-model/chat-bots)}
                    (when cust-uuid {:document/creator cust-uuid})))]
    {:status 200 :body (pr-str {:document {:db/id (:db/id doc)}})}))

(defpage early-access [:post "/api/v1/early-access"] [req]
  (if-let [cust (get-in req [:auth :cust])]
    (do
      (pc.early-access/create-request cust (edn/read-string (slurp (:body req))))
      (pc.early-access/approve-request cust)
      {:status 200 :body (pr-str {:msg "Thanks!" :access-request-granted? true})})
    {:status 401 :body (pr-str {:error :not-logged-in
                                :msg "Please log in to request early access."})}))

(def app (defpage/collect-routes))
