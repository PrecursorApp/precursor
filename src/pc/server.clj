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
            [pc.http.sente :as sente]
            [pc.auth :as auth]
            [pc.auth.google :refer (google-client-id)]
            [pc.models.access-grant :as access-grant-model]
            [pc.models.cust :as cust]
            [pc.models.doc :as doc-model]
            [pc.models.layer :as layer]
            [pc.models.permission :as permission-model]
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
            [ring.util.response :refer (redirect)]
            [slingshot.slingshot :refer (try+ throw+)])
  (:import java.util.UUID))

(defn ssl? [req]
  (or (= :https (:scheme req))
      (= "https" (get-in req [:headers "x-forwarded-proto"]))))

(def bucket-doc-ids (atom #{}))

(defn clean-bucket-doc-ids []
  (swap! bucket-doc-ids (fn [b]
                          (set/intersection b (set (keys @sente/document-subs))))))

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
                                 :google-client-id (google-client-id)}
                                (when-let [cust (-> req :auth :cust)]
                                  {:cust {:email (:cust/email cust)
                                          :uuid (:cust/uuid cust)
                                          :name (:cust/name cust)}})))
            (if-let [redirect-doc (doc-model/find-by-invalid-id db (Long/parseLong document-id))]
              (redirect (str "/document/" (:db/id redirect-doc)))
              ;; TODO: this should be a 404...
              (redirect "/")))))

   (GET "/" req
        (let [cust-uuid (get-in req [:auth :cust :cust/uuid])
              doc (doc-model/create-public-doc! (when cust-uuid {:document/creator cust-uuid}))]
          (redirect (str "/document/" (:db/id doc)))))

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
            (let [doc (doc-model/create-public-doc! {})]
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
               :headers {"Location" (str (url/map->URL {:host (profile/hostname)
                                                        :protocol (if (ssl? req) "https" "http")
                                                        :port (if (ssl? req)
                                                                (profile/https-port)
                                                                (profile/http-port))
                                                        :path (or (get parsed-state "redirect-path") "/")
                                                        :query (get parsed-state "redirect-query")}))}}))))

   (POST "/logout" {{redirect-to :redirect-to} :params :as req}
         (when-let [cust (-> req :auth :cust)]
           (cust/retract-session-key! cust))
         {:status 302
          :body ""
          :headers {"Location" (str (url/map->URL {:host (profile/hostname)
                                                   :protocol (if (ssl? req) "https" "http")
                                                   :port (if (ssl? req)
                                                           (profile/https-port)
                                                           (profile/http-port))
                                                   :path redirect-to}))}
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

   (ANY "*" [] {:status 404 :body "Page not found."})))

(defn log-request [req resp ms]
  (when-not (re-find #"^/cljs" (:uri req))
    (let [cust (-> req :auth :cust)
          cust-str (when cust (str (:db/id cust) " (" (:cust/email cust) ")"))]
      (log/infof "%s: %s %s for %s %s in %sms" (:status resp) (:request-method req) (:uri req) (:remote-addr req) cust-str ms))))

(defn logging-middleware [handler]
  (fn [req]
    (let [start (time/now)
          resp (handler req)
          stop (time/now)]
      (try
        (log-request req resp (time/in-millis (time/interval start stop)))
        (catch Exception e
          (log/error e)))
      resp)))

(defn ssl-middleware [handler]
  (fn [req]
    (if (or (not (profile/force-ssl?))
            (ssl? req))
      (handler req)
      {:status 301
       :headers {"Location" (str (url/map->URL {:host (profile/hostname)
                                                :protocol "https"
                                                :port (profile/https-port)
                                                :path (:uri req)
                                                :query (:query-string req)}))}
       :body ""})))

(defn exception-middleware [handler]
  (fn [req]
    (try+
      (handler req)
      (catch :status t
        (log/error t)
        {:status (:status t)
         :body (:public-message t)})
      (catch Object e
        (log/error e)
        (.printStackTrace e)
        {:status 500
         :body "Sorry, something completely unexpected happened!"}))))

(defn auth-middleware [handler]
  (fn [req]
    (let [db (pcd/default-db)
          cust (some->> req :session :http-session-key (cust/find-by-http-session-key db))
          access-grant (some->> req :params :access-grant-token (access-grant-model/find-by-token db))]
      (when (and cust access-grant)
        (permission-model/convert-access-grant access-grant cust {:document/id (:access-grant/document access-grant)
                                                                  :cust/uuid (:cust/uuid cust)
                                                                  :transaction/broadcast true}))
      (handler (cond cust (assoc-in req [:auth :cust] cust)
                     access-grant (assoc-in req [:auth :access-grant] access-grant)
                     :else req)))))

(defn wrap-wrap-reload
  "Only applies wrap-reload middleware in development"
  [handler]
  (if (profile/prod?)
    handler
    (wrap-reload handler)))

(defn handler [sente-state]
  (->
   (app sente-state)
   (auth-middleware)
   (wrap-anti-forgery)
   (wrap-session {:store (cookie-store {:key (profile/http-session-key)})
                  :cookie-attrs {:http-only true
                                 :expires (time-format/unparse (:rfc822 time-format/formatters) (time/from-now (time/years 1))) ;; expire one year after the server starts up
                                 :max-age (* 60 60 24 365)
                                 :secure (profile/force-ssl?)}})
   (ssl-middleware)
   (wrap-wrap-reload)
   (exception-middleware)
   (logging-middleware)
   (site)))

(defn start [sente-state]
  (def server (httpkit/run-server (handler sente-state)
                                  {:port (profile/http-port)})))

(defn stop []
  (server))

(defn restart []
  (stop)
  (start @sente/sente-state))


(defn init []
  (let [sente-state (sente/init)]
    (start sente-state))
  (datomic/init))
