(ns pc.http.routes
  (:require [cemerick.url :as url]
            [cheshire.core :as json]
            [clojure.set :as set]
            [crypto.equality :as crypto]
            [defpage.core :as defpage :refer (defpage)]
            [hiccup.page]
            [pc.assets]
            [pc.analytics :as analytics]
            [pc.auth :as auth]
            [pc.auth.google :as google-auth]
            [pc.convert :as convert]
            [pc.datomic :as pcd]
            [pc.http.doc :as doc-http]
            [pc.http.lb :as lb]
            [pc.http.handlers.custom-domain :as custom-domain]
            [pc.http.sente :as sente]
            [pc.http.urls :as urls]
            [pc.models.access-request :as access-request-model]
            [pc.models.chat-bot :as chat-bot-model]
            [pc.models.cust :as cust-model]
            [pc.models.doc :as doc-model]
            [pc.models.layer :as layer-model]
            [pc.models.team :as team-model]
            [pc.profile :as profile]
            [pc.render :as render]
            [pc.util.md5 :as md5]
            [pc.views.content :as content]
            [ring.middleware.anti-forgery :as csrf]
            [ring.util.anti-forgery :refer (anti-forgery-field)]
            [ring.util.response :refer (redirect)]))

(defn common-view-data [req]
  (merge
   {:CSRFToken csrf/*anti-forgery-token*
    :google-client-id (google-auth/google-client-id)
    :sente-id (-> req :session :sente-id)
    :hostname (profile/hostname)}
   (when-let [cust (-> req :auth :cust)]
     {:cust (cust-model/read-api cust)})
   (when-let [team (-> req :team)]
     {:team (team-model/public-read-api team)})
   (when-let [subdomain (-> req :subdomain)]
     {:subdomain subdomain})))

(defpage root "/" [req]
  (if (:subdomain req)
    (let [db (pcd/default-db)]
      (cond (not (auth/logged-in? req))
            (redirect (str (url/map->URL {:host (profile/hostname)
                                          :protocol (if (profile/force-ssl?)
                                                      "https"
                                                      (name (:scheme req)))
                                          :port (if (profile/force-ssl?)
                                                  443
                                                  (:server-port req))
                                          :path "/login"
                                          :query {:redirect-subdomain (:subdomain req)
                                                  :redirect-csrf-token csrf/*anti-forgery-token*}})))


            (and (auth/logged-in? req)
                 (not (:team req)))
            {:status 200
             :body (hiccup.page/html5
                    {}
                    "Please email <a href=\"mailto:hi@precursorapp.com\">hi@precursorapp.com</a> to claim this domain for your team")}

            (and (auth/logged-in? req)
                 (:team req)
                 (not (auth/has-team-permission? db (:team req) (:auth req) :admin))
                 (seq (access-request-model/find-by-team-and-cust (pcd/default-db) (:team req) (get-in req [:auth :cust]))))
            {:status 200
             :body (hiccup.page/html5
                    {}
                    [:p "Thanks for requesting access, we'll send you an email when your request is granted."]
                    [:p "In the meantime, you can make something on " [:a {:href (urls/root)}
                                                                       (urls/root)]])}

            (and (auth/logged-in? req)
                 (:team req)
                 (not (auth/has-team-permission? db (:team req) (:auth req) :admin)))
            {:status 200
             :body (hiccup.page/html5
                    {}
                    [:html
                     [:body
                      [:span
                       [:form {:action "/request-team-permission" :method "post"}
                        (anti-forgery-field)
                        [:input {:type "submit" :value "Request permission to join this team"}]]]]])}

            (and (auth/logged-in? req)
                 (:team req)
                 (auth/has-team-permission? db (:team req) (:auth req) :admin))
            (let [doc (doc-model/create-team-doc!
                       (:team req)
                       (merge {:document/chat-bot (rand-nth chat-bot-model/chat-bots)}
                              (when-let [cust-uuid (get-in req [:cust :cust/uuid])]
                                {:document/creator cust-uuid})))]
              (redirect (str "/document/" (:db/id doc))))))
    (let [cust-uuid (get-in req [:auth :cust :cust/uuid])
          ;; if we get to here,
          doc (doc-model/create-public-doc!
               (merge {:document/chat-bot (rand-nth chat-bot-model/chat-bots)}
                      (when cust-uuid {:document/creator cust-uuid})
                      ;; needs default permissions set
                      (when (:team req) {:document/team (:db/id (:team req))})))]
      (if cust-uuid
        (redirect (str "/document/" (:db/id doc)))
        (content/app (merge (common-view-data req)
                            {:initial-document-id (:db/id doc)}))))))

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

(defpage document [:get "/document/:document-id" {:document-id #"[0-9]+"}] [req]
  (let [document-id (-> req :params :document-id)
        db (pcd/default-db)
        doc (doc-model/find-by-team-and-id db (:team req) (Long/parseLong document-id))]
    (if doc
      (content/app (merge (common-view-data req)
                          {:initial-document-id (:db/id doc)
                           :meta-image (urls/doc-png (:db/id doc))}
                          ;; TODO: Uncomment this once we have a way to send just the novelty to the client.
                          ;; (when (auth/has-document-permission? db doc (-> req :auth) :admin)
                          ;;   {:initial-entities (layer/find-by-document db doc)})
                          ))
      (if-let [redirect-doc (doc-model/find-by-team-and-invalid-id db (:team req) (Long/parseLong document-id))]
        (redirect (str "/document/" (:db/id redirect-doc)))
        {:status 404
         :body "Document not found"}))))

(defn image-cache-headers [db doc]
  (let [last-modified-instant (or (doc-http/last-modified-instant db doc)
                                  (java.util.Date.))]
    {"Cache-Control" "no-cache"
     "ETag" (format "\"%s\"" (md5/encode (str (pc.utils/inspect last-modified-instant))))
     "Last-Modified" (->> last-modified-instant
                       (clj-time.coerce/from-date)
                       (clj-time.format/unparse (clj-time.format/formatters :rfc822)))}))

(defpage doc-svg "/document/:document-id.svg" [req]
  (let [document-id (-> req :params :document-id)
        db (pcd/default-db)
        doc (doc-model/find-by-team-and-id db (:team req) (Long/parseLong document-id))]
    (cond (nil? doc)
          (if-let [doc (doc-model/find-by-team-and-invalid-id db (:team req) (Long/parseLong document-id))]
            (redirect (str "/document/" (:db/id doc) ".svg"))

            {:status 404
             ;; TODO: Maybe return a "not found" image.
             :body "Document not found."})

          (auth/has-document-permission? db doc (-> req :auth) :read)
          (if (= :head (:request-method req))
            {:status 200
             :headers (merge {"Content-Type" "image/svg+xml"}
                             (image-cache-headers db doc))
             :pc/doc doc
             :body ""}
            (let [layers (layer-model/find-by-document db doc)]
              {:status 200
               :headers (merge {"Content-Type" "image/svg+xml"}
                               (image-cache-headers db doc))
               :pc/doc doc
               :body (render/render-layers layers :invert-colors? (-> req :params :printer-friendly (= "false")))}))

          (auth/logged-in? req)
          {:status 403
           ;; TODO: use an image here
           :body "Please request permission to access this document"}

          :else
          {:status 401
           ;; TODO: use an image here
           :body "Please log in so that we can check if you have permission to access this document"})))

(defpage doc-png "/document/:document-id.png" [req]
  (let [document-id (-> req :params :document-id)
        db (pcd/default-db)
        doc (doc-model/find-by-team-and-id db (:team req) (Long/parseLong document-id))]
    (cond (nil? doc)
          (if-let [redirect-doc (doc-model/find-by-team-and-invalid-id db (:team req) (Long/parseLong document-id))]
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
            (let [layers (layer-model/find-by-document db doc)]
              {:status 200
               :headers (merge {"Content-Type" "image/png"}
                               (image-cache-headers db doc))
               :pc/doc doc
               :body (convert/svg->png (render/render-layers layers
                                                             :invert-colors? (-> req :params :printer-friendly (= "false"))
                                                             :size-limit 800))}))

          (auth/logged-in? req)
          {:status 403
           ;; TODO: use an image here
           :body "Please request permission to access this document"}

          :else
          {:status 401
           ;; TODO: use an image here
           :body "Please log in so that we can check if you have permission to access this document"})))

(defn frontend-response
  "Response to send for requests that the frontend will route"
  [req]
  (content/app (common-view-data req)))

(defpage new-doc "/new" [req]
  (frontend-response req))

(defn outer-page
  "Response to send for requests that need a document-id that the frontend will route"
  [req]
  (if (:subdomain req)
    ;; TODO: figure out what to do with outer pages on subdomains, need to
    ;;       solve the extraneous entity-id problem first
    (custom-domain/redirect-to-main req)
    (let [cust-uuid (get-in req [:auth :cust :cust/uuid])
          ;; TODO: Have to figure out a way to create outer pages without creating extraneous entity-ids
          doc (doc-model/create-public-doc!
               (merge {:document/chat-bot (rand-nth chat-bot-model/chat-bots)}
                      (when cust-uuid {:document/creator cust-uuid})))]
      (content/app (merge (common-view-data req)
                          {:initial-document-id (:db/id doc)})))))

(defpage pricing "/pricing" [req]
  (outer-page req))

(defpage early-access "/early-access" [req]
  (outer-page req))

(defpage early-access-type "/early-access/:type" [req]
  (outer-page req))

(defpage home "/home" [req]
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

(def app (defpage/collect-routes))
