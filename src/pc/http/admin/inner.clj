(ns pc.http.admin.inner
  (:require [cemerick.url :as url]
            [cheshire.core :as json]
            [crypto.equality :as crypto]
            [defpage.core :as defpage :refer (defpage)]
            [hiccup.core :as hiccup]
            [pc.http.admin.auth :as auth]
            [pc.views.content :as content]
            [ring.middleware.anti-forgery]))

(defpage base "/admin" [req]
  (hiccup/html (content/layout {} [:div "Congratulations on logging in!"])))

(defn wrap-require-login [handler]
  (fn [req]
    (if (auth/authorized? req)
      (handler req)
      {:status 401 :body "Please log in."})))

(def app
  (->> (defpage/find-defpages)
    (defpage/map-wrap-matching-routes wrap-require-login)
    (defpage/combine-routes)))
