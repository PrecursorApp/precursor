(ns pc.server
  (:require [cemerick.url :as url]
            [cheshire.core :as json]
            [clojure.core.async :as async]
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
            [pc.http.sente :as sente]
            [pc.auth :as auth]
            [pc.auth.google :refer (google-client-id)]
            [pc.models.cust :as cust]
            [pc.models.layer :as layer]
            [pc.profile :as profile]
            [pc.less :as less]
            [pc.views.content :as content]
            [pc.utils :refer (inspect)]
            [ring.middleware.anti-forgery :refer (wrap-anti-forgery)]
            [ring.middleware.session :refer (wrap-session)]
            [ring.middleware.session.cookie :refer (cookie-store)]
            [ring.util.response :refer (redirect)]
            [slingshot.slingshot :refer (try+ throw+)])
  (:import java.util.UUID))

(defn ssl? [req]
  (or (= :https (:scheme req))
      (= "https" (get-in req [:headers "x-forwarded-proto"]))))

(defn app [sente-state]
  (routes
   (POST "/api/entity-ids" request
         (datomic/entity-id-request (-> request :body slurp edn/read-string :count)))
   (POST "/api/transact" request
         (let [body (-> request :body slurp edn/read-string)]
           (datomic/transact! (:datoms body) (:document-id body) (get-in request [:session :uid]))))
   (GET "/api/document/:id" [id]
        ;; TODO: should probably verify document exists
        ;; TODO: take tx-date as a param
        {:status 200
         :body (pr-str {:layers (layer/find-by-document (pcd/default-db) {:db/id (Long/parseLong id)})})})
   (GET "/document/:document-id" [document-id :as req]
        (content/app (merge {:CSRFToken ring.middleware.anti-forgery/*anti-forgery-token*
                             :google-client-id (google-client-id)}
                            (when-let [cust (-> req :auth :cust)]
                              {:cust {:email (:cust/email cust)}}))))
   (GET "/" []
        (let [[document-id] (pcd/generate-eids (pcd/conn) 1)]
          @(d/transact (pcd/conn) [{:db/id document-id :document/name "Untitled"}])
          (redirect (str "/document/" document-id))))
   ;; Group newcomers into buckets with bucket-count users in each bucket.
   ;; TODO: exclude documents that didn't start out as buckets.
   (GET ["/bucket/:bucket-count" :bucket-count #"[0-9]+"] [bucket-count]
        (let [bucket-count (Integer/parseInt bucket-count)]
          (if-let [eid (ffirst (sort-by (comp - count last)
                                        (filter (fn [[doc-id subs]]
                                                  (< (count subs) bucket-count))
                                                @sente/document-subs)))]
            (redirect (str "/document/" eid))
            (redirect "/"))))
   (GET "/interesting" []
        {:status 200
         :body (str
                "<html></body>"
                (clojure.string/join
                 " "
                 (or (seq (for [doc-id (db-admin/interesting-doc-ids {:layer-threshold 10})]
                            (format "<p><a href=\"/document/%s\">%s</a></p>" doc-id doc-id)))
                     ["Nothing interesting today :("]))
                "</body></html")})

   (GET ["/interesting/:layer-count" :layer-count #"[0-9]+"] [layer-count]
        {:status 200
         :body (str
                "<html></body>"
                (clojure.string/join
                 " "
                 (or (seq (for [doc-id (db-admin/interesting-doc-ids {:layer-threshold (Integer/parseInt layer-count)})]
                            (format "<p><a href=\"/document/%s\">%s</a></p>" doc-id doc-id)))
                     ["Nothing interesting today :("]))
                "</body></html")})

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

            (let [cust (auth/cust-from-google-oauth-code code (some-> req :session :uid))]
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
        {:status 500
         :body "Sorry, something completely unexpected happened!"}))))

(defn auth-middleware [handler]
  (fn [req]
    (if-let [cust (some->> req :session :http-session-key (cust/find-by-http-session-key (pcd/default-db)))]
      (handler (assoc req :auth {:cust cust}))
      (handler req))))

(defn handler [sente-state]
  (->
   (app sente-state)
   (sente/wrap-user-id)
   (auth-middleware)
   (wrap-anti-forgery)
   (wrap-session {:store (cookie-store {:key (profile/http-session-key)})
                  :cookie-attrs {:http-only true
                                 :expires (time-format/unparse (:rfc822 time-format/formatters) (time/from-now (time/years 1))) ;; expire one year after the server starts up
                                 :max-age (* 60 60 24 365)
                                 :secure (profile/force-ssl?)}})
   (ssl-middleware)
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
