(ns pc.views.team
  (:require [cemerick.url :as url]
            [hiccup.core :as h]
            [pc.http.urls :as urls]
            [pc.profile :as profile]
            [pc.views.content :as content]
            [ring.middleware.anti-forgery :as csrf]
            [ring.util.anti-forgery :refer (anti-forgery-field)]))

(def logomark
  [:i {:class "icon-logomark"}
   [:svg {:class "iconpile" :viewBox "0 0 100 100"}
    [:path {:class "fill-logomark" :d "M43,100H29.5V39H43V100z M94,33.8C90.9,22,83.3,12.2,72.8,6.1C62.2,0,50-1.6,38.2,1.6 C26.5,4.7,16.6,12.3,10.6,22.8C4.5,33.3,2.9,45.6,6,57.4l1.7,6.4l12.7-3.4l-1.7-6.4c-4.6-17.2,5.6-35,22.9-39.6 c8.3-2.2,17.1-1.1,24.6,3.2c7.5,4.3,12.8,11.3,15.1,19.7c4.6,17.2-5.6,35-22.9,39.6L52,78.5l3.4,12.7l6.4-1.7 C86.1,83.1,100.5,58,94,33.8z"}]]])

(def google
  [:i {:class "icon-google-logo"}
   [:svg {:class "iconpile" :viewBox "0 0 100 100"}
    [:path {:class "fill-google" :d "M53.8,0C35.5,0,25.6,11.6,25.6,24.5c0,9.8,7.1,21,21.6,21h3.7c0,0-1,2.4-1,4.8c0,3.5,1.2,5.4,3.9,8.4 c-25,1.5-35.1,11.6-35.1,22.5c0,9.5,9.1,18.9,28.2,18.9c22.6,0,34.4-12.6,34.4-24.9c0-8.7-4.3-13.5-15.3-21.7 c-3.2-2.5-3.9-4.1-3.9-6c0-2.7,1.6-4.5,2.2-5.1c1-1.1,2.8-2.3,3.5-2.9c3.7-3.1,8.9-7.7,8.9-17c0-6.3-2.6-11.8-8.6-16.9h7.3L80.9,0 L53.8,0L53.8,0z M48.8,4.1c3.3,0,6.1,1.2,9,3.6c3.2,2.9,8.4,10.8,8.4,20.5c0,10.5-8.2,13.4-12.6,13.4c-2.2,0-4.8-0.6-6.9-2.1 C41.8,36.4,37,28,37,17.9C37,8.9,42.4,4.1,48.8,4.1z M56,62.7c1.4,0,2.4,0.1,2.4,0.1s3.3,2.4,5.6,4.1c5.4,4.2,8.7,7.5,8.7,13.2 c0,7.9-7.4,14.1-19.3,14.1c-13.1,0-23.1-6.1-23.1-16C30.4,70,37.2,62.9,56,62.7L56,62.7z"}]]])

(def twitter
  [:i {:class "icon-twitter"}
   [:svg {:class "iconpile" :viewBox "0 0 100 100"}
    [:path {:class "fill-twitter" :d "M100,19c-3.7,1.6-7.6,2.7-11.8,3.2c4.2-2.5,7.5-6.6,9-11.4c-4,2.4-8.4,4.1-13,5c-3.7-4-9.1-6.5-15-6.5 c-11.3,0-20.5,9.2-20.5,20.5c0,1.6,0.2,3.2,0.5,4.7c-17.1-0.9-32.2-9-42.3-21.4c-1.8,3-2.8,6.6-2.8,10.3c0,7.1,3.6,13.4,9.1,17.1 c-3.4-0.1-6.5-1-9.3-2.6c0,0.1,0,0.2,0,0.3c0,9.9,7.1,18.2,16.5,20.1c-1.7,0.5-3.5,0.7-5.4,0.7c-1.3,0-2.6-0.1-3.9-0.4 c2.6,8.2,10.2,14.1,19.2,14.2c-7,5.5-15.9,8.8-25.5,8.8c-1.7,0-3.3-0.1-4.9-0.3c9.1,5.8,19.9,9.2,31.4,9.2 c37.7,0,58.4-31.3,58.4-58.4c0-0.9,0-1.8-0.1-2.7C93.8,26.7,97.2,23.1,100,19z"}]]])

(def nav-head
  [:div.nav.nav-head ; keep up to date with outer/nav-head
   [:a.nav-link.nav-logo {:href "https://precursorapp.com/home" :title "Precursor"} "Precursor"]
   [:a.nav-link.nav-app  {:href "https://precursorapp.com/new"  :title "Launch"}    "App"]])

(def nav-foot
  [:div.nav.nav-foot ; keep up to date with outer/nav-head
   [:a.nav-link.nav-logo {:href "https://precursorapp.com" :title "Precursor"} logomark]
   [:a.nav-link.nav-twitter
    {:title "@PrecursorApp"
     :href "https://twitter.com/PrecursorApp"}
    twitter]])

(defn login-interstitial [req]
  (h/html
   (content/layout
    {}
    [:div.page-team
     nav-head
     [:div.team-login
      [:div.team-login-content
       [:h1 "Join your team!"]
       [:h4 (str (h/h (:subdomain req)) "." (profile/hostname))]
       [:div.calls-to-action
        [:a#vendor.google-login {:href (str (url/map->URL
                                      {:host (profile/hostname)
                                       :protocol (if (profile/force-ssl?)
                                                   "https"
                                                   (name (:scheme req)))
                                       :port (if (profile/force-ssl?)
                                               443
                                               (:server-port req))
                                       :path "/login"
                                       :query {:redirect-subdomain (:subdomain req)
                                               :redirect-csrf-token csrf/*anti-forgery-token*}}))}
        google
        [:div.google-text "Sign in with Google"]]]]]
     nav-foot])))

(defn request-domain [req]
  (h/html
   (content/layout
    {}
    [:div.page-team
     nav-head
     [:div.team-login
      [:div.team-login-content
       [:h1 "Create your team!"]
       [:h4 (str (h/h (:subdomain req)) "." (profile/hostname))]
       [:div.calls-to-action
        [:a {:href (str (url/map->URL
                         {:host (profile/hostname)
                          :protocol (if (profile/force-ssl?)
                                      "https"
                                      (name (:scheme req)))
                          :port (if (profile/force-ssl?)
                                  443
                                  (:server-port req))
                          :path "/trial"
                          :query {:subdomain (h/h (:subdomain req))}}))}
         "Start a trial to create this team"]]]]
     nav-foot])))

(defn request-access [req]
  (h/html
   (content/layout
    {}
    [:div.page-team
     nav-head
     [:div.team-login
      [:div.team-login-content
       [:h1 "Join your team!"]
       [:h4 (str (h/h (:subdomain req)) "." (profile/hostname))]
       [:div.calls-to-action
        [:form {:action "/request-team-permission" :method "post"}
         (anti-forgery-field)
         [:button {:type "submit" :value ""}
          "Request permission to join this team"]]]]]
     nav-foot])))

(defn requested-access [req]
  (h/html
   (content/layout
    {}
    [:div.page-team
     nav-head
     [:div.team-login
      [:div.team-login-content
       [:h1 "Join your team!"]
       [:h4 (str (h/h (:subdomain req)) "." (profile/hostname))]
       [:p "We got your request. We'll send you an email when the owner grants your request."]
       [:p "You can also give the owner "
        [:a {:href (str (urls/from-doc (:team/intro-doc (:team req))
                                       :query {:overlay "team-settings"}))}
         "this link"]
        " to review your request."]
       [:p ]]]
     nav-foot])))
