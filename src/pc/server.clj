(ns pc.server
  (:require [cemerick.url :as url]
            [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.set :as set]
            [clojure.tools.logging :as log]
            [clojure.tools.reader.edn :as edn]
            [clj-time.core :as time]
            [clj-time.format :as time-format]
            [compojure.core :refer (defroutes routes GET POST ANY)]
            [compojure.handler :refer (site)]
            [compojure.route]
            [crypto.equality :as crypto]
            [datomic.api :refer [db q] :as d]
            [org.httpkit.server :as httpkit]
            [pc.admin.db :as db-admin]
            [pc.datomic :as pcd]
            [pc.http.datomic :as datomic]
            [pc.http.doc :refer (duplicate-doc)]
            [pc.http.lb :as lb]
            [pc.http.handlers.logging :as logging-handler]
            [pc.http.handlers.errors :as errors-handler]
            [pc.http.handlers.ssl :as ssl-handler]
            [pc.http.sente :as sente]
            [pc.assets]
            [pc.auth :as auth]
            [pc.auth.google :refer (google-client-id)]
            [pc.early-access]
            [pc.models.access-grant :as access-grant-model]
            [pc.models.chat-bot :as chat-bot-model]
            [pc.models.cust :as cust]
            [pc.models.doc :as doc-model]
            [pc.models.layer :as layer]
            [pc.models.flag :as flag-model]
            [pc.models.permission :as permission-model]
            [pc.rollbar :as rollbar]
            [pc.profile :as profile]
            [pc.less :as less]
            [pc.views.content :as content]
            [pc.views.blog :as blog]
            [pc.utils :refer (inspect)]
            [pc.convert :refer (svg->png)]
            [pc.render :refer (render-layers)]
            [ring.middleware.anti-forgery :refer (wrap-anti-forgery)]
            [ring.middleware.reload :refer (wrap-reload)]
            [ring.middleware.session :refer (wrap-session)]
            [ring.middleware.session.cookie :refer (cookie-store)]
            [ring.util.response :refer (redirect)])
  (:import java.util.UUID))

(def bucket-doc-ids (atom #{}))

(defn clean-bucket-doc-ids []
  (swap! bucket-doc-ids (fn [b]
                          (set/intersection b (set (keys @sente/document-subs))))))

(defn frontend-response
  "Response to send for requests that the frontend will route"
  [req]
  (content/app (merge {:CSRFToken ring.middleware.anti-forgery/*anti-forgery-token*
                       :google-client-id (google-client-id)
                       :sente-id (-> req :session :sente-id)}
                      (when-let [cust (-> req :auth :cust)]
                        {:cust (cust/read-api cust)}))))

(defn outer-page
  "Response to send for requests that need a document-id that the frontend will route"
  [req]
  (let [cust-uuid (get-in req [:auth :cust :cust/uuid])
        ;; TODO: Have to figure out a way to create outer pages without creating extraneous entity-ids
        doc (doc-model/create-public-doc!
             (merge {:document/chat-bot (rand-nth chat-bot-model/chat-bots)}
                    (when cust-uuid {:document/creator cust-uuid})))]
    (content/app (merge {:CSRFToken ring.middleware.anti-forgery/*anti-forgery-token*
                         :google-client-id (google-client-id)
                         :sente-id (-> req :session :sente-id)
                         :initial-document-id (:db/id doc)}
                        (when-let [cust (-> req :auth :cust)]
                          {:cust (cust/read-api cust)})))))

;; TODO: make this reloadable without reloading the server
(defn app [sente-state]
  (routes
   (POST "/api/entity-ids" request
         (datomic/entity-id-request (-> request :body slurp edn/read-string :count)))

   (GET "/document/:document-id.svg" [document-id :as req]
        (let [db (pcd/default-db)
              doc (doc-model/find-by-id db (Long/parseLong document-id))]
          (cond (nil? doc)
                (if-let [doc (doc-model/find-by-invalid-id db (Long/parseLong document-id))]
                  (redirect (str "/document/" (:db/id doc) ".svg"))

                  {:status 404
                   ;; TODO: Maybe return a "not found" image.
                   :body "Document not found."})

                (auth/has-document-permission? db doc (-> req :auth) :read)
                (let [layers (layer/find-by-document db doc)]
                  {:status 200
                   :headers {"Content-Type" "image/svg+xml"}
                   :body (render-layers layers :invert-colors? (-> req :params :printer-friendly (= "false")))})

                (auth/logged-in? req)
                {:status 403
                 ;; TODO: use an image here
                 :body "Please request permission to access this document"}

                :else
                {:status 401
                 ;; TODO: use an image here
                 :body "Please log in so that we can check if you have permission to access this document"})))
   (GET "/document/:document-id.png" [document-id :as req]
        (let [db (pcd/default-db)
              doc (doc-model/find-by-id db (Long/parseLong document-id))]
          (cond (nil? doc)
                (if-let [redirect-doc (doc-model/find-by-invalid-id db (Long/parseLong document-id))]
                  (redirect (str "/document/" (:db/id redirect-doc) ".png"))

                  {:status 404
                   ;; TODO: Return a "not found" image.
                   :body "Document not found."})

                (auth/has-document-permission? db doc (-> req :auth) :read)
                (let [layers (layer/find-by-document db doc)]
                  {:status 200
                   :headers {"Content-Type" "image/png"}
                   :body (svg->png (render-layers layers
                                                  :invert-colors? (-> req :params :printer-friendly (= "false"))
                                                  :size-limit 2000))})

                (auth/logged-in? req)
                {:status 403
                 ;; TODO: use an image here
                 :body "Please request permission to access this document"}

                :else
                {:status 401
                 ;; TODO: use an image here
                 :body "Please log in so that we can check if you have permission to access this document"})))

   (GET ["/document/:document-id" :document-id #"[0-9]+"] [document-id :as req]
        (let [db (pcd/default-db)
              doc (doc-model/find-by-id db (Long/parseLong document-id))]
          (if doc
            (content/app (merge {:CSRFToken ring.middleware.anti-forgery/*anti-forgery-token*
                                 :google-client-id (google-client-id)
                                 :sente-id (-> req :session :sente-id)
                                 :initial-document-id (:db/id doc)}
                                ;; TODO: Uncomment this once we have a way to send just the novelty to the client.
                                ;;       Also need a way to handle transactions before sente connects
                                ;; (when (auth/has-document-permission? db doc (-> req :auth) :admin)
                                ;;   {:initial-entities (layer/find-by-document db doc)})
                                (when-let [cust (-> req :auth :cust)]
                                  {:cust (cust/read-api cust)})))
            (if-let [redirect-doc (doc-model/find-by-invalid-id db (Long/parseLong document-id))]
              (redirect (str "/document/" (:db/id redirect-doc)))
              ;; TODO: this should be a 404...
              (redirect "/")))))

   (GET "/new" req
        (frontend-response req))

   (POST "/api/v1/document/new" req
         (let [cust-uuid (get-in req [:auth :cust :cust/uuid])
               doc (doc-model/create-public-doc!
                    (merge {:document/chat-bot (rand-nth chat-bot-model/chat-bots)}
                           (when cust-uuid {:document/creator cust-uuid})))]
           {:status 200 :body (pr-str {:document {:db/id (:db/id doc)}})}))

   (POST "/api/v1/early-access" req
         (if-let [cust (get-in req [:auth :cust])]
           (do
             (pc.early-access/create-request cust (edn/read-string (slurp (:body req))))
             (pc.early-access/approve-request cust)
             {:status 200 :body (pr-str {:msg "Thanks!" :access-request-granted? true})})
           {:status 401 :body (pr-str {:error :not-logged-in
                                       :msg "Please log in to request early access."})}))

   (GET "/" req
        (let [cust-uuid (get-in req [:auth :cust :cust/uuid])
              doc (doc-model/create-public-doc!
                   (merge {:document/chat-bot (rand-nth chat-bot-model/chat-bots)}
                          (when cust-uuid {:document/creator cust-uuid})))]
          (if cust-uuid
            (redirect (str "/document/" (:db/id doc)))
            (content/app (merge {:CSRFToken ring.middleware.anti-forgery/*anti-forgery-token*
                                 :google-client-id (google-client-id)
                                 :sente-id (-> req :session :sente-id)
                                 :initial-document-id (:db/id doc)})))))

   (GET "/pricing" req
        (outer-page req))

   (GET "/early-access" req
        (outer-page req))

   (GET "/early-access/:type" req
        (outer-page req))

   (GET "/home" req
        (outer-page req))

   ;; Group newcomers into buckets with bucket-count users in each bucket.
   (GET ["/bucket/:bucket-count" :bucket-count #"[0-9]+"] [bucket-count]
        (let [bucket-count (Integer/parseInt bucket-count)]
          (when (< 100 (count @bucket-doc-ids)
                   (clean-bucket-doc-ids)))
          (if-let [doc-id (ffirst (sort-by (comp - count last)
                                           (filter (fn [[doc-id subs]]
                                                     ;; only send them to docs created by the bucketing
                                                     (and (contains? @bucket-doc-ids doc-id)
                                                          ;; only send them to docs that are occupied
                                                          (< 0 (count subs) bucket-count)))
                                                   @sente/document-subs)))]
            (redirect (str "/document/" doc-id))
            (let [doc (doc-model/create-public-doc! {:document/chat-bot (rand-nth chat-bot-model/chat-bots)})]
              (swap! bucket-doc-ids conj (:db/id doc))
              (redirect (str "/document/" (:db/id doc)))))))

   (GET "/interesting" []
        (content/interesting (db-admin/interesting-doc-ids {:layer-threshold 10})))

   (GET ["/interesting/:layer-count" :layer-count #"[0-9]+"] [layer-count]
        (content/interesting (db-admin/interesting-doc-ids {:layer-threshold (Integer/parseInt layer-count)})))

   (GET "/occupied" []
        ;; TODO: fix whatever is causing this :(
        (swap! sente/document-subs (fn [ds]
                                     (reduce (fn [acc1 [k s]]
                                               (assoc acc1 k (dissoc s "dummy-ajax-post-fn")))
                                             {} ds)))
        {:status 200
         :body (str
                "<html></body>"
                (clojure.string/join
                 " "
                 (or (seq (for [[doc-id subs] (sort-by first @sente/document-subs)]
                            (format "<p><a href=\"/document/%s\">%s</a> with %s users</p>" doc-id doc-id (count subs))))
                     ["Nothing occupied right now :("]))
                "</body></html")})

   (compojure.route/resources "/" {:root "public"
                                   :mime-types {:svg "image/svg"}})
   (GET "/chsk" req ((:ajax-get-or-ws-handshake-fn sente-state) req))
   (POST "/chsk" req ((:ajax-post-fn sente-state) req))
   (GET "/auth/google" {{code :code state :state} :params :as req}
        (let [parsed-state (-> state url/url-decode json/decode)]
          (if (not (crypto/eq? (get parsed-state "csrf-token")
                               ring.middleware.anti-forgery/*anti-forgery-token*))
            {:status 400
             :body "There was a problem logging you in."}

            (let [cust (auth/cust-from-google-oauth-code code req)]
              {:status 302
               :body ""
               :session (assoc (:session req)
                               :http-session-key (:cust/http-session-key cust))
               :headers {"Location" (str (or (get parsed-state "redirect-path") "/")
                                         (when-let [query (get parsed-state "redirect-query")]
                                           (str "?" query)))}}))))

   (POST "/logout" {{redirect-to :redirect-to} :params :as req}
         (when-let [cust (-> req :auth :cust)]
           (cust/retract-session-key! cust))
         {:status 302
          :body ""
          :headers {"Location" (str redirect-to)}
          :session nil})

   (GET "/email/welcome/:template.gif" [template]
        {:status 200
         :body (content/email-welcome template {:CSRFToken ring.middleware.anti-forgery/*anti-forgery-token*})})

   (POST "/duplicate/:document-name" [document-name :as req]

         (redirect (str "/document/" (duplicate-doc document-name (-> req :auth :cust)))))

   (GET "/blog" []
        {:status 200
         :body (blog/render-page nil)})
   (GET "/blog/:slug" [slug]
        {:status 200
         :body (blog/render-page slug)})

   ;; TODO: protect this route with admin credentials
   ;;       and move to non-web ns
   (GET "/admin/reload-assets" []
        (pc.assets/reload-assets)
        {:status 200})

   (GET "/health-check" req
        (lb/health-check-response req))

   (ANY "*" [] {:status 404 :body "Page not found."})))

(defn auth-middleware [handler]
  (fn [req]
    (let [db (pcd/default-db)
          cust (some->> req :session :http-session-key (cust/find-by-http-session-key db))
          access-grant (some->> req :params :access-grant-token (access-grant-model/find-by-token db))
          permission (some->> req :params :auth-token (permission-model/find-by-token db))]
      (when (and cust access-grant)
        (permission-model/convert-access-grant access-grant cust {:document/id (:access-grant/document access-grant)
                                                                  :cust/uuid (:cust/uuid cust)
                                                                  :transaction/broadcast true}))
      (handler (-> (cond cust (assoc-in req [:auth :cust] cust)
                         access-grant (assoc-in req [:auth :access-grant] access-grant)
                         :else req)
                 (assoc-in [:auth :permission] permission))))))

(defn wrap-wrap-reload
  "Only applies wrap-reload middleware in development"
  [handler]
  (if (profile/prod?)
    handler
    (wrap-reload handler)))

(defn assoc-sente-id [req response sente-id]
  (if (= (get-in req [:session :sente-id]) sente-id)
    response
    (-> response
      (assoc :session (:session response (:session req)))
      (assoc-in [:session :sente-id] sente-id))))

(defn wrap-sente-id [handler]
  (fn [req]
    (let [sente-id (or (get-in req [:session :sente-id])
                       (str (UUID/randomUUID)))]
      (if-let [response (handler (assoc-in req [:session :sente-id] sente-id))]
        (assoc-sente-id req response sente-id)))))

(defn handler [sente-state]
  (-> (app sente-state)
    (auth-middleware)
    (wrap-anti-forgery)
    (wrap-sente-id)
    (wrap-session {:store (cookie-store {:key (profile/http-session-key)})
                   :cookie-attrs {:http-only true
                                  :expires (time-format/unparse (:rfc822 time-format/formatters) (time/from-now (time/years 1))) ;; expire one year after the server starts up
                                  :max-age (* 60 60 24 365)
                                  :secure (profile/force-ssl?)}})
    (ssl-handler/wrap-force-ssl {:host (profile/hostname)
                                 :https-port (profile/https-port)
                                 :force-ssl? (profile/force-ssl?)})
    (wrap-wrap-reload)
    (errors-handler/wrap-errors)
    (logging-handler/wrap-logging)
    (site)))

(defn start [sente-state]
  (def server (httpkit/run-server (handler sente-state)
                                  {:port (profile/http-port)})))

(defn stop [& {:keys [timeout]
               :or {timeout 0}}]
  (server :timeout timeout))

(defn restart []
  (stop)
  (start @sente/sente-state))

(defn init []
  (let [sente-state (sente/init)]
    (start sente-state))
  (datomic/init))

(defn shutdown []
  (sente/shutdown :sleep-ms 250)
  (stop :timeout 1000))
