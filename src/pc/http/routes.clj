(ns pc.http.routes
  (:require [cemerick.url :as url]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [crypto.equality :as crypto]
            [datomic.api :as d]
            [defpage.core :as defpage :refer (defpage)]
            [hiccup.page]
            [pc.analytics :as analytics]
            [pc.assets]
            [pc.auth :as auth]
            [pc.auth.google :as google-auth]
            [pc.convert :as convert]
            [pc.datomic :as pcd]
            [pc.http.doc :as doc-http]
            [pc.http.handlers.custom-domain :as custom-domain]
            [pc.http.lb :as lb]
            [pc.http.sente :as sente]
            [pc.http.urls :as urls]
            [pc.models.access-request :as access-request-model]
            [pc.models.chat-bot :as chat-bot-model]
            [pc.models.cust :as cust-model]
            [pc.models.doc :as doc-model]
            [pc.models.invoice :as invoice-model]
            [pc.models.issue :as issue-model]
            [pc.models.layer :as layer-model]
            [pc.models.team :as team-model]
            [pc.profile :as profile]
            [pc.render :as render]
            [pc.util.md5 :as md5]
            [pc.views.content :as content]
            [pc.views.invoice :as invoice-view]
            [pc.views.team :as team-view]
            [ring.middleware.anti-forgery :as csrf]
            [ring.util.response :refer (redirect)])
  (:import [java.io ByteArrayOutputStream]
           [java.util UUID]))

(defn common-view-data [req]
  (merge
   {:CSRFToken csrf/*anti-forgery-token*
    :google-client-id (google-auth/google-client-id)
    :sente-id (-> req :session :sente-id)
    :hostname (profile/hostname)}
   (when-let [cust (-> req :auth :cust)]
     {:cust (cust-model/read-api cust)
      :admin? (contains? cust-model/admin-emails (:cust/email cust))})
   (when-let [team (-> req :team)]
     {:team (team-model/public-read-api team)})
   (when-let [subdomain (-> req :subdomain)]
     {:subdomain subdomain})))

(defpage root "/" [req]
  (if (:subdomain req)
    (let [db (pcd/default-db)]
      (cond (not (auth/logged-in? req))
            (team-view/login-interstitial req)


            (and (auth/logged-in? req)
                 (not (:team req)))
            {:status 200
             :body (team-view/request-domain req)}

            (and (auth/logged-in? req)
                 (:team req)
                 (not (auth/has-team-permission? db (:team req) (:auth req) :admin))
                 (seq (access-request-model/find-by-team-and-cust (pcd/default-db) (:team req) (get-in req [:auth :cust]))))
            {:status 200
             :body (team-view/requested-access req)}

            (and (auth/logged-in? req)
                 (:team req)
                 (not (auth/has-team-permission? db (:team req) (:auth req) :admin)))
            {:status 200
             :body (team-view/request-access req)}

            (and (auth/logged-in? req)
                 (:team req)
                 (auth/has-team-permission? db (:team req) (:auth req) :admin))
            (let [doc (doc-model/create-team-doc!
                       (:team req)
                       (merge {:document/chat-bot (rand-nth chat-bot-model/chat-bots)}
                              (when-let [cust-uuid (get-in req [:cust :cust/uuid])]
                                {:document/creator cust-uuid})))]
              (redirect (str "/document/" (:db/id doc))))))
    (if-let [cust-uuid (get-in req [:auth :cust :cust/uuid])]
      (redirect (str "/document/" (:db/id (doc-model/create-public-doc!
                                           (merge {:document/chat-bot (rand-nth chat-bot-model/chat-bots)}
                                                  (when cust-uuid {:document/creator cust-uuid})
                                                  ;; needs default permissions set
                                                  (when (:team req) {:document/team (:db/id (:team req))}))))))
      (content/app (common-view-data req)))))

(defpage request-team-permission [:post "/request-team-permission"] [req]
  (let [team (:team req)
        cust (get-in req [:auth :cust])]
    (when-not team
      {:throw 400
       :public-message "Sorry, we couldn't find that team."})
    (when-not cust
      {:throw 400
       :public-message "Please log in before requesting access."})
    (do (access-request-model/create-team-request team cust {:transaction/team (:db/id team)
                                                             :cust/uuid (:cust/uuid cust)
                                                             :transaction/broadcast true})
        (redirect "/"))))

(defn doc-resp [doc req & {:keys [view-data]}]
  (let [read-access? (auth/has-document-permission? (pcd/default-db) doc (-> req :auth) :read)]
    (content/app (merge (common-view-data req)
                        {:initial-document-id (:db/id doc)
                         :meta-image (urls/png-from-doc doc)
                         :meta-url (urls/from-doc doc)
                         :meta-title (when read-access? (:document/name doc))
                         :initial-entities (if read-access?
                                             [(doc-model/read-api doc)]
                                             [{:db/id (:db/id doc)}])}
                        view-data))))

(defn parse-doc-id [doc-id-param]
  (Long/parseLong (re-find #"[0-9]+$" doc-id-param)))

(defn handle-doc [req]
  (let [document-id (some-> req :params :document-id parse-doc-id)
        db (pcd/default-db)
        doc (doc-model/find-by-team-and-id db (:team req) document-id)]
    (if doc
      (doc-resp doc req)
      (if-let [redirect-doc (doc-model/find-by-team-and-invalid-id db (:team req) document-id)]
        (redirect (str "/document/" (:db/id redirect-doc)))
        {:status 404
         :body (if-let [doc (doc-model/find-by-id db document-id)]
                 (hiccup.page/html5
                  [:body "This document lives on a different domain: " [:a {:href (urls/from-doc doc)}
                                                                        (urls/from-doc doc)]])
                 "Document not found")}))))

(defpage document [:get "/document/:document-id" {:document-id #"[A-Za-z0-9_-]*-{0,1}[0-9]+"}] [req]
  (handle-doc req))

(defpage document-overlay [:get "/document/:document-id/:overlay" {:document-id #"[A-Za-z0-9_-]*-{0,1}[0-9]+" :overlay #"[\w-]+"}] [req]
  (handle-doc req))

(def private-layers [{:layer/opacity 1.0, :layer/stroke-width 1.0, :layer/end-x 389.5996, :entity/type :layer, :layer/start-y 120.0, :layer/text "Please log in or request access to view it.", :layer/stroke-color "black", :layer/start-x 100.0, :layer/fill "none", :layer/type :layer.type/text, :layer/end-y 100.0} {:layer/opacity 1.0, :layer/stroke-width 1.0, :layer/end-x 373.5547, :entity/type :layer, :layer/start-y 90.0, :layer/text "Sorry, this document is private.", :layer/stroke-color "black", :layer/start-x 100.0, :layer/fill "none", :layer/type :layer.type/text, :layer/end-y 70.0}])

(defn image-cache-headers [db doc]
  (let [last-modified-instant (or (doc-http/last-modified-instant db doc)
                                  (java.util.Date.))]
    {"Cache-Control" "no-cache; private"
     "ETag" (format "\"%s\"" (md5/encode (str last-modified-instant)))
     "Last-Modified" (->> last-modified-instant
                       (clj-time.coerce/from-date)
                       (clj-time.format/unparse (clj-time.format/formatters :rfc822)))}))

(defpage doc-svg "/document/:document-id.svg" [req]
  (let [document-id (-> req :params :document-id parse-doc-id)
        db (pcd/default-db)
        doc (doc-model/find-by-team-and-id db (:team req) document-id)]
    (cond (nil? doc)
          (if-let [doc (doc-model/find-by-team-and-invalid-id db (:team req) document-id)]
            (redirect (str "/document/" (:db/id doc) ".svg"))

            {:status 404
             ;; TODO: Maybe return a "not found" image.
             :body "Document not found."})

          (auth/has-document-permission? db doc (-> req :auth) :read)
          (if (= :head (:request-method req))
            {:status 200
             :headers (merge {"Content-Type" "image/svg+xml; charset=UTF-8"}
                             (image-cache-headers db doc))
             :pc/doc doc
             :body ""}
            (let [as-of (some-> req :params :as-of (Long/parseLong))
                  layer-db (if as-of (d/as-of db as-of) db)
                  layers (layer-model/find-by-document layer-db doc)]
              {:status 200
               :headers (merge {"Content-Type" "image/svg+xml; charset=UTF-8"}
                               (when (-> req :params :dl)
                                 {"Content-Disposition" (format "attachment; filename=\"precursor-document-%s.svg\""
                                                                (:db/id doc))})
                               (image-cache-headers layer-db doc))
               :pc/doc doc
               :body (render/render-layers layers :invert-colors? (-> req :params :printer-friendly (= "false")))}))

          (auth/logged-in? req)
          {:status 200
           :headers {"Content-Type" "image/svg+xml; charset=UTF-8"
                     "Cache-Control" "no-cache; private"}
           :body (render/render-layers private-layers :invert-colors? (-> req :params :printer-friendly (= "false")))}

          :else
          {:status 200
           :headers {"Content-Type" "image/svg+xml; charset=UTF-8"
                     "Cache-Control" "no-cache; private"}
           :body (render/render-layers private-layers :invert-colors? (-> req :params :printer-friendly (= "false")))})))

(defpage doc-png "/document/:document-id.png" [req]
  (let [document-id (-> req :params :document-id parse-doc-id)
        db (pcd/default-db)
        doc (doc-model/find-by-team-and-id db (:team req) document-id)]
    (cond (nil? doc)
          (if-let [redirect-doc (doc-model/find-by-team-and-invalid-id db (:team req) document-id)]
            (redirect (str "/document/" (:db/id redirect-doc) ".png"))

            {:status 404
             ;; TODO: Return a "not found" image.
             :body "Document not found."})

          (auth/has-document-permission? db doc (-> req :auth) :read)
          (if (= :head (:request-method req))
            {:status 200
             :headers (merge {"Content-Type" "image/png"}
                             (image-cache-headers db doc))
             :pc/doc doc
             :body ""}
            (let [as-of (some-> req :params :as-of (Long/parseLong))
                  layer-db (if as-of (d/as-of db as-of) db)
                  layers (layer-model/find-by-document layer-db doc)]
              {:status 200
               :headers (merge {"Content-Type" "image/png"}
                               (when (-> req :params :dl)
                                 {"Content-Disposition" (format "attachment; filename=\"precursor-document-%s.png\""
                                                                (:db/id doc))})
                               (image-cache-headers layer-db doc))
               :pc/doc doc
               :body (convert/svg->png (render/render-layers layers
                                                             :invert-colors? (-> req :params :printer-friendly (= "false"))
                                                             :size-limit 800))}))

          (auth/logged-in? req)
          {:status 200
           :headers {"Content-Type" "image/png"
                     "Cache-Control" "no-cache; private"}
           :body (convert/svg->png (render/render-layers private-layers
                                                         :invert-colors? (-> req :params :printer-friendly (= "false"))
                                                         :size-limit 800))}

          :else
          {:status 200
           :headers {"Content-Type" "image/png"
                     "Cache-Control" "no-cache; private"}
           :body (convert/svg->png (render/render-layers private-layers
                                                         :invert-colors? (-> req :params :printer-friendly (= "false"))
                                                         :size-limit 800))})))

(defpage doc-pdf "/document/:document-id.pdf" [req]
  (let [document-id (-> req :params :document-id parse-doc-id)
        db (pcd/default-db)
        doc (doc-model/find-by-team-and-id db (:team req) document-id)]
    (cond (nil? doc)
          (if-let [redirect-doc (doc-model/find-by-team-and-invalid-id db (:team req) document-id)]
            (redirect (str "/document/" (:db/id redirect-doc) ".pdf"))

            {:status 404
             ;; TODO: Return a "not found" image.
             :body "Document not found."})

          (auth/has-document-permission? db doc (-> req :auth) :read)
          (let [as-of (some-> req :params :as-of (Long/parseLong))
                layer-db (if as-of (d/as-of db as-of) db)
                layers (layer-model/find-by-document layer-db doc)]
            {:status 200
             :headers (merge {"Content-Type" "application/pdf"}
                             (when (-> req :params :dl)
                               {"Content-Disposition" (format "attachment; filename=\"precursor-document-%s.pdf\""
                                                              (:db/id doc))})
                             (image-cache-headers layer-db doc))
             :pc/doc doc
             :body (convert/svg->pdf (render/render-layers layers
                                                           :invert-colors? (-> req :params :printer-friendly (= "false")))
                                     (render/svg-props layers))})

          (auth/logged-in? req)
          {:status 200
           :headers {"Content-Type" "application/pdf"
                     "Cache-Control" "no-cache; private"}
           :body (convert/svg->pdf (render/render-layers private-layers :invert-colors? (-> req :params :printer-friendly (= "false")))
                                   (render/svg-props private-layers))}

          :else
          {:status 200
           :headers {"Content-Type" "application/pdf"
                     "Cache-Control" "no-cache; private"}
           :body (convert/svg->pdf (render/render-layers private-layers :invert-colors? (-> req :params :printer-friendly (= "false")))
                                   (render/svg-props private-layers))})))

(defn frontend-response
  "Response to send for requests that the frontend will route"
  [req]
  (content/app (common-view-data req)))

(defpage new-doc "/new" [req]
   (if (:subdomain req)
    (if (and (:team req)
             (auth/logged-in? req)
             (auth/has-team-permission? (pcd/default-db) (:team req) (:auth req) :admin))
      (frontend-response req)
      (custom-domain/redirect-to-main req))
    (frontend-response req)))

(defn outer-page
  "Response to send for requests that need a document-id that the frontend will route"
  [req]
  (if (:subdomain req)
    ;; TODO: figure out what to do with outer pages on subdomains, need to
    ;;       solve the extraneous entity-id problem first
    (custom-domain/redirect-to-main req)
    (let [cust-uuid (get-in req [:auth :cust :cust/uuid])]
      (content/app (common-view-data req)))))

(defpage search-issues "/issues/search" [req]
  (if (:subdomain req)
    (custom-domain/redirect-to-main req)
    (outer-page req)))

(defpage single-issue "/issues/:issue-uuid" [req]
  (if (:subdomain req)
    (custom-domain/redirect-to-main req)
    (let [db (pcd/default-db)
          frontend-id (-> req :params :issue-uuid (UUID/fromString))
          issue (issue-model/find-by-frontend-id db frontend-id)]
      (if-not issue
        {:body "Sorry, we couldn't find that issue."
         :status 404}
        (let [doc (:issue/document issue)]
          (doc-resp doc req :view-data {:initial-issue-entities [(issue-model/read-api issue)]
                                        :meta-description (:issue/description issue)}))))))

(defpage issue "/issues" [req]
  (outer-page req))

(defpage pricing "/pricing" [req]
  (outer-page req))

(defpage team-features "/features/team" [req]
  (outer-page req))

(defpage early-access "/early-access" [req]
  (redirect "/trial"))

(defpage early-access-type "/early-access/:type" [req]
  (redirect "/trial"))

(defpage trial "/trial" [req]
  (outer-page req))

(defpage trial-type "/trial/:type" [req]
  (redirect "/trial"))

(defpage home "/home" [req]
  (outer-page req))

(defpage product-hunt "/product-hunt" [req]
  (outer-page req))

(defpage product-hunt "/designer-news" [req]
  (outer-page req))

(def bucket-doc-ids (atom #{}))

(defn clean-bucket-doc-ids []
  (swap! bucket-doc-ids (fn [b]
                          (set/intersection b (set (keys @sente/document-subs))))))

(defpage bucket [:get "/bucket/:bucket-count" {:bucket-count #"[0-9]+"}] [req]
  (if (:subdomain req)
    (custom-domain/redirect-to-main req)
    (let [bucket-count (Integer/parseInt (-> req :params :bucket-count))]
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
          (redirect (str "/document/" (:db/id doc))))))))

(defpage sente-handshake "/chsk" [req]
  ((:ajax-get-or-ws-handshake-fn @sente/sente-state) req))

(defpage sente-ajax-handshake [:post "/chsk"] [req]
  ((:ajax-post-fn @sente/sente-state) req))

(defpage google-auth "/auth/google" [{{code :code state :state} :params :as req}]
  (let [parsed-state (-> state url/url-decode json/decode)]
    (if (not (crypto/eq? (get parsed-state "csrf-token")
                         csrf/*anti-forgery-token*))
      {:status 400
       :body "There was a problem logging you in."}

      (if-let [subdomain (get parsed-state "redirect-subdomain")]
        {:status 302
         :body ""
         :headers {"Location" (urls/make-url "/auth/google"
                                             :subdomain subdomain
                                             :query {:code code
                                                     :state (-> parsed-state
                                                              (dissoc "redirect-subdomain" "redirect-csrf-token")
                                                              (assoc "csrf-token" (get parsed-state "redirect-csrf-token"))
                                                              json/encode
                                                              url/url-encode)})}}
        (let [cust (auth/cust-from-google-oauth-code code req)
              query (get parsed-state "redirect-query")]
          {:status 302
           :body ""
           :session (assoc (:session req)
                           :http-session-key (:cust/http-session-key cust))
           :headers {"Location" (str (or (get parsed-state "redirect-path") "/")
                                     (when query (str "?" query)))}})))))

(defpage invoice "/team/:team-uuid/plan/invoice/:invoice-id" [req]
  (let [db (pcd/default-db)
        team (some->> req :params :team-uuid UUID/fromString (team-model/find-by-uuid db))
        invoice-id (some->> req :params :invoice-id Long/parseLong)]
    (if (auth/has-team-permission? db team (:auth req) :admin)
      (if-let [invoice (invoice-model/find-by-team-and-client-part db team invoice-id)]
        {:body (io/input-stream (invoice-view/invoice-pdf team invoice))
         :headers {"Content-Type" "application/pdf"}
         :status 200}
        {:body "Unable to find invoice"
         :status 400})
      (if (auth/logged-in? req)
        {:status 403
         :body "Please request permission to join this team"}
        {:status 401
         :body "Please login to view the invoice"}))))

(defpage login "/login" [req]
  (analytics/track-signup-clicked req)
  (redirect (google-auth/oauth-uri csrf/*anti-forgery-token*
                                   :redirect-path (get-in req [:params :redirect-path] "/")
                                   :redirect-query (get-in req [:params :redirect-query])
                                   :redirect-subdomain (get-in req [:params :redirect-subdomain])
                                   :redirect-csrf-token (get-in req [:params :redirect-csrf-token]))))

(defpage logout [:post "/logout"] [{{redirect-to :redirect-to} :params :as req}]
  (when-let [cust (-> req :auth :cust)]
    (cust-model/retract-session-key! cust))
  {:status 302
   :body ""
   :headers {"Location" (str redirect-to)}
   :session nil})

(defpage email-template "/email/welcome/:template.gif" [req]
  (let [template (-> req :params :template)]
    {:status 200
     :body (content/email-welcome template {:CSRFToken csrf/*anti-forgery-token*})}))

(defpage duplicate-doc [:post "/duplicate/:document-name"] [req]
  (let [document-name (-> req :params :document-name)]
    (redirect (str "/document/" (doc-http/duplicate-doc document-name (-> req :auth :cust))))))

(defpage reload-assets "/admin/reload-assets" [req]
  (pc.assets/reload-assets)
  {:status 200})

(defpage health-check "/health-check" [req]
  (lb/health-check-response req))

(defpage robots-txt "/robots.txt" [req]
  {:status 200
   :body "User-agent: Twitterbot\nDisallow:\n"})

(def app (defpage/collect-routes))
