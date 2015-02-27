(ns pc.http.admin.inner
  (:require [cemerick.url :as url]
            [cheshire.core :as json]
            [crypto.equality :as crypto]
            [defpage.core :as defpage :refer (defpage)]
            [hiccup.core :as hiccup]
            [pc.http.admin.auth :as auth]
            [pc.views.admin :as admin-content]
            [pc.views.content :as content]
            [ring.middleware.anti-forgery]))

(defpage base "/admin" [req]
  (hiccup/html (content/layout {}
                               [:div {:style "padding: 40px"}
                                [:p "Congratulations on logging in!"]
                                [:p "Here's a cumulative graph of users. It needs some work."
                                 (admin-content/users-graph)
                                 (admin-content/early-access-users)]])))

(defn wrap-require-login [handler]
  (fn [req]
    (if (auth/authorized? req)
      (handler req)
      {:status 401 :body "Please log in."})))

(def app
  (->> (defpage/find-defpages)
    (defpage/map-wrap-matching-routes wrap-require-login)
    (defpage/combine-routes)))
