(ns pc.http.admin.inner
  (:require [cemerick.url :as url]
            [cheshire.core :as json]
            [crypto.equality :as crypto]
            [defpage.core :as defpage :refer (defpage)]
            [hiccup.core :as hiccup]
            [pc.datomic :as pcd]
            [pc.http.admin.auth :as auth]
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
                                [:p "Here's a cumulative graph of users. It needs some work."
                                 (admin-content/users-graph)]])))

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

(defn wrap-require-login [handler]
  (fn [req]
    (if (auth/authorized? req)
      (handler req)
      {:status 401 :body "Please log in."})))

(def app
  (->> (defpage/find-defpages)
    (defpage/map-wrap-matching-routes wrap-require-login)
    (defpage/combine-routes)))
