(ns pc.http.admin.outer
  (:require [cemerick.url :as url]
            [cheshire.core :as json]
            [crypto.equality :as crypto]
            [defpage.core :as defpage :refer (defpage)]
            [hiccup.core :as hiccup]
            [pc.http.admin.auth :as auth]
            [pc.views.content :as content]
            [ring.middleware.anti-forgery]))

(defpage root "/" [req]
  (if (auth/authorized? req)
    {:status 302 :body "" :headers {"Location" "/admin"}}
    (hiccup/html (content/layout {} [:div [:a {:href (auth/auth-url ring.middleware.anti-forgery/*anti-forgery-token*)}
                                           "Please log in with Google"]
                                     "."]))))

(defpage root "/health-check" [req]
  {:status 200 :body ":up"})

(defpage google-auth "/auth/google" [req]
  (let [{:strs [code state]} (-> req :query-string url/query->map pc.utils/inspect)
        parsed-state (-> state url/url-decode json/decode pc.utils/inspect)]
    (if (not (crypto/eq? (get parsed-state "csrf-token")
                         ring.middleware.anti-forgery/*anti-forgery-token*))
      {:status 400
       :body "There was a problem logging you in."}
      (let [admin (auth/admin-from-google-oauth-code code req)]
        {:status 302
         :body ""
         :session (assoc (:session req)
                         :admin-http-session-key (:admin/http-session-key admin))
         :headers {"Location" "/admin"}}))))

(def app (defpage/collect-routes))
