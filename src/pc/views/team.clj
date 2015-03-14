(ns pc.views.team
  (:require [cemerick.url :as url]
            [hiccup.core :as h]
            [pc.http.urls :as urls]
            [pc.profile :as profile]
            [pc.views.content :as content]
            [ring.middleware.anti-forgery :as csrf]
            [ring.util.anti-forgery :refer (anti-forgery-field)]))

(defn request-domain []
  (h/html
   (content/layout
    {}
    [:div
     "Please email "
     [:a {:href "mailto:hi@precursorapp.com"}
      "hi@precursorapp.com"]
     " to claim this domain for your team"])))

(defn request-access []
  (h/html
   (content/layout
    {}
    [:form {:action "/request-team-permission" :method "post"}
     (anti-forgery-field)
     [:input {:type "submit" :value "Request permission to join this team"}]])))

(defn requested-access []
  (h/html
   (content/layout
    {}
    [:p "Thanks for requesting access, we'll send you an email when your request is granted."]
    [:p "In the meantime, you can make something on " [:a {:href (urls/root)}
                                                       (urls/root)]])))

(defn login-interstitial [req]
  (h/html
   (content/layout
    {}
    [:a {:href (str (url/map->URL {:host (profile/hostname)
                                   :protocol (if (profile/force-ssl?)
                                               "https"
                                               (name (:scheme req)))
                                   :port (if (profile/force-ssl?)
                                           443
                                           (:server-port req))
                                   :path "/login"
                                   :query {:redirect-subdomain (:subdomain req)
                                           :redirect-csrf-token csrf/*anti-forgery-token*}}))}
     "Please log in"])))
