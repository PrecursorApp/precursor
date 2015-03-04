(ns pc.http.admin.inner
  (:require [cemerick.url :as url]
            [cheshire.core :as json]
            [crypto.equality :as crypto]
            [defpage.core :as defpage :refer (defpage)]
            [hiccup.core :as hiccup]
            [pc.datomic :as pcd]
            [pc.http.admin.auth :as auth]
            [pc.http.sente :as sente]
            [pc.models.cust :as cust-model]
            [pc.models.flag :as flag-model]
            [pc.views.admin :as admin-content]
            [pc.views.content :as content]
            [ring.middleware.anti-forgery]
            [slingshot.slingshot :refer (try+ throw+)])
  (:import java.util.UUID))

(defpage base "/admin" [req]
  (hiccup/html (content/layout {}
                               [:div {:style "padding: 40px"}
                                [:p "Congratulations on logging in!"]
                                [:h2 "Early access"]
                                (admin-content/early-access-users)
                                [:h2 "User Growth"]
                                (admin-content/users-graph)])))

(defpage base "/graphs" [req]
  (hiccup/html (content/layout {}
                               [:div {:style "padding: 40px"}
                                [:h2 "User Growth"]
                                (admin-content/users-graph)])))

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

(defpage clients "/clients" [req]
  {:status 200 :body (hiccup/html (content/layout {} (admin-content/clients @sente/client-stats @sente/document-subs)))})

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


(defn wrap-require-login [handler]
  (fn [req]
    (if (auth/authorized? req)
      (handler req)
      {:status 401 :body "Please log in."})))

(def app
  (->> (defpage/find-defpages)
    (defpage/map-wrap-matching-routes wrap-require-login)
    (defpage/combine-routes)))
