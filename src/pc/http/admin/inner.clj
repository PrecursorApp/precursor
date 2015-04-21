(ns pc.http.admin.inner
  (:require [cemerick.url :as url]
            [cheshire.core :as json]
            [crypto.equality :as crypto]
            [datomic.api :as d]
            [defpage.core :as defpage :refer (defpage)]
            [hiccup.core :as hiccup]
            [pc.admin.db :as db-admin]
            [pc.datomic :as pcd]
            [pc.http.admin.auth :as auth]
            [pc.http.sente :as sente]
            [pc.http.team :as team-http]
            [pc.http.urls :as urls]
            [pc.models.cust :as cust-model]
            [pc.models.doc :as doc-model]
            [pc.models.flag :as flag-model]
            [pc.profile :as profile]
            [pc.stripe.dev :as stripe-dev]
            [pc.views.admin :as admin-content]
            [pc.views.content :as content]
            [ring.middleware.anti-forgery]
            [slingshot.slingshot :refer (try+ throw+)])
  (:import java.util.UUID))

(defpage base "/admin" [req]
  (hiccup/html (content/layout {}
                               [:div {:style "padding: 40px"}
                                [:div [:a {:href "/teams"} "Teams"]]
                                [:div [:a {:href "/users"} "Users"]]
                                [:div [:a {:href "/growth"} "Growth"]]
                                [:div [:a {:href "/early-access"} "Early Access"]]
                                [:div [:a {:href "/graphs"} "User Graphs"]]
                                [:div [:a {:href "/clients"} "Clients"]]
                                [:div [:a {:href "/occupied"} "Occupied"]]
                                [:div [:a {:href "/interesting"} "Interesting"]]
                                [:div [:a {:href "/upload"} "Upload to Google CDN"]]
                                (when (profile/fetch-stripe-events?)
                                  [:div [:a {:href "/stripe-events"} "View Stripe events"]])])))

(defpage early-access "/early-access" [req]
  (hiccup/html (content/layout {}
                               [:div {:style "padding: 40px"}
                                [:h2 "Early access"]
                                (admin-content/early-access-users)])))

(defpage teams "/teams" [req]
  (hiccup/html (content/layout {}
                               [:div {:style "padding: 40px"}
                                [:h2 "Teams"]
                                (admin-content/teams)])))

(defpage graphs "/graphs" [req]
  (hiccup/html (content/layout {}
                               [:div {:style "padding: 40px"}
                                [:h2 "User Growth"]
                                (admin-content/users-graph)])))

(defpage growth "/growth" [req]
  (hiccup/html (content/layout {}
                               [:div {:style "padding: 40px"}
                                [:h2 "Growth"]
                                (admin-content/growth)])))

(defpage clients "/clients" [req]
  {:status 200 :body (hiccup/html (content/layout {} (admin-content/clients @sente/client-stats @sente/document-subs)))})

(defpage interesting "/interesting" [req]
  (hiccup/html (content/layout {} (admin-content/interesting (db-admin/interesting-doc-ids {:layer-threshold 10})))))

(defpage interesting-count [:get "/interesting/:layer-count" {:layer-count #"[0-9]+"}] [layer-count]
  (hiccup/html (content/layout {} (admin-content/interesting (db-admin/interesting-doc-ids (Integer/parseInt layer-count))))))

(defpage user-activity "/user/:email" [req]
  (let [cust (->> req :params :email (cust-model/find-by-email (pcd/default-db)))]
    (if (seq cust)
      (hiccup/html
       (content/layout {}
                       (admin-content/user-info cust)
                       (admin-content/interesting (take 100 (doc-model/find-touched-by-cust (pcd/default-db) cust)))))
      {:status 404
       :body "Couldn't find user with that email"})))

(defpage doc-activity "/document/:document-id" [req]
  (let [doc (->> req :params :document-id (Long/parseLong) (doc-model/find-by-id (pcd/default-db)))]
    (if (seq doc)
      (hiccup/html
       (content/layout {}
                       (admin-content/doc-info doc (:auth req))))
      {:status 404
       :body "Couldn't find that document"})))

(defpage doc-replay-helper "/replay-helper/:document-id" [req]
  (let [doc (->> req :params :document-id (Long/parseLong) (doc-model/find-by-id (pcd/default-db)))]
    (if (seq doc)
      (hiccup/html
       (content/layout {}
                       (admin-content/replay-helper doc)))
      {:status 404
       :body "Couldn't find that document"})))

(defpage users "/users" [req]
  (hiccup/html (content/layout {} (admin-content/users))))

(defpage occupied "/occupied" [req]
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

(defpage upload "/upload" [req]
  (hiccup/html (content/layout {} (admin-content/upload-files))))

(defpage upload "/stripe-events" [req]
  (hiccup/html (content/layout {} (admin-content/stripe-events))))

(defpage refresh-client [:post "/refresh-client-stats"] [req]
  (if-let [client-id (get-in req [:params "client-id"])]
    (sente/fetch-stats client-id)
    (when (get-in req [:params "refresh-all"])
      (doseq [[client-id _] @sente/client-stats]
        (sente/fetch-stats client-id))))
  (Thread/sleep 500)
  {:status 302 :headers {"Location" "/clients"} :body ""})

(defpage refresh-browser [:post "/refresh-client-browser"] [req]
  (when-let [client-id (get-in req [:params "client-id"])]
    (sente/refresh-browser client-id))
  (Thread/sleep 500)
  {:status 302 :headers {"Location" "/clients"} :body ""})

(defpage grant-early-access [:post "/grant-early-access"] [req]
  (let [uuid (some-> req :params (get "cust-uuid") (UUID/fromString))]
    (when (empty? (str uuid))
      (throw+ {:status 400 :public-message "No customer specified"}))
    (let [cust (cust-model/find-by-uuid (pcd/default-db) uuid)]
      (when (empty? cust)
        (throw+ {:status 400 :public-message "No customer found"}))
      (flag-model/add-flag cust :flags/private-docs)
      {:status 200 :body (hiccup/html [:div
                                       [:p "All right, granted access to " (:cust/email cust) "."]
                                       [:a {:href "/admin"}
                                        "Go back to the admin page"]
                                       "."])})))

(defpage create-team [:post "/create-team"] [req]
  (let [subdomain (some-> req :params (get "subdomain"))
        cust (some-> req :params (get "cust-email") (#(cust-model/find-by-email (pcd/default-db) %)))]
    (when (empty? subdomain)
      (throw+ {:status 400 :public-message "No subdomain"}))
    (when (empty? cust)
      (throw+ {:status 400 :public-message "No customer found"}))
    (let [team (team-http/setup-new-team subdomain cust)]
      {:status 200 :body (hiccup/html [:div
                                       [:p "All right, created team "
                                        [:a {:href (urls/root :subdomain (:team/subdomain team))}
                                         (:team/subdomain team)]
                                        "."]
                                       [:a {:href "/teams"}
                                        "Go back to the teams page"]
                                       "."])})))

(defpage retry-stripe-event [:post "/retry-stripe-event/:evt-id"] [req]
  (stripe-dev/retry-event (get-in req [:params :evt-id]))
  {:status 200
   :body (str "retried " (get-in req [:params :evt-id]))})

(defn wrap-require-login [handler]
  (fn [req]
    (if (auth/authorized? req)
      (handler req)
      {:status 401 :body "Please log in."})))

(def app
  (->> (defpage/find-defpages)
    (defpage/map-wrap-matching-routes wrap-require-login)
    (defpage/combine-routes)))
