(ns pc.http.routes.api
  (:require [cemerick.url :as url]
            [clojure.tools.reader.edn :as edn]
            [defpage.core :as defpage :refer (defpage)]
            [pc.auth :as auth]
            [pc.datomic :as pcd]
            [pc.early-access]
            [pc.http.handlers.custom-domain :as custom-domain]
            [pc.models.doc :as doc-model]
            [pc.models.chat-bot :as chat-bot-model]
            [pc.profile :as profile]))

(defpage new [:post "/api/v1/document/new"] [req]
  (if (:subdomain req)
    (if (and (:team req)
             (auth/logged-in? req)
             (auth/has-team-permission? (pcd/default-db) (:team req) (:auth req) :admin))
      (let [doc (doc-model/create-team-doc!
                 (:team req)
                 (merge {:document/chat-bot (rand-nth chat-bot-model/chat-bots)}
                        (when-let [cust-uuid (get-in req [:cust :cust/uuid])]
                          {:document/creator cust-uuid})))]
        {:status 200 :body (pr-str {:document {:db/id (:db/id doc)}})})
      {:status 400 :body (pr-str {:error :unauthorized-to-team
                                  :redirect-url (str (url/map->URL {:host (profile/hostname)
                                                                    :protocol (name (:scheme req))
                                                                    :port (:server-port req)
                                                                    :path "/new"
                                                                    :query (:query-string req)}))
                                  :msg "You're unauthorized to make documents in this subdomain. Please request access."})})
    (let [cust-uuid (get-in req [:auth :cust :cust/uuid])
          doc (doc-model/create-public-doc!
               (merge {:document/chat-bot (rand-nth chat-bot-model/chat-bots)}
                      (when cust-uuid {:document/creator cust-uuid})))]
      {:status 200 :body (pr-str {:document {:db/id (:db/id doc)}})})))

(defpage early-access [:post "/api/v1/early-access"] [req]
  (if-let [cust (get-in req [:auth :cust])]
    (do
      (pc.early-access/create-request cust (edn/read-string (slurp (:body req))))
      (pc.early-access/approve-request cust)
      {:status 200 :body (pr-str {:msg "Thanks!" :access-request-granted? true})})
    {:status 401 :body (pr-str {:error :not-logged-in
                                :msg "Please log in to request early access."})}))

(def app (defpage/collect-routes))
