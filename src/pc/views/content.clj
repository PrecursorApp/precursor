(ns pc.views.content
  (:require [cheshire.core :as json]
            [hiccup.core :as h]
            [pc.profile :refer (prod-assets?)]))

(defn layout [view-data & content]
  [:html
   [:head
    [:title "Precursor - Mockups from the future"]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"}]
    [:link.css-styles {:rel "stylesheet", :href (str "/css/app.css?rand=" (Math/random))}]
    [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/css?family=Roboto:500,900,100,300,700,400" :type "text/css"}]
    [:script {:type "text/javascript"}
     (format "window.Precursor = JSON.parse('%s')" (json/encode view-data))]]
   [:body
    [:div.alerts-container]
    content]])

(defn app* [view-data]
  (layout
   view-data
   [:input.history {:style "display:none;"}]
   [:div#player-container]
   [:div#app-container]
   [:div.debugger-container]
   [:div#app]
   (if (prod-assets?)
     [:script {:type "text/javascript" :src (str "/js/vendor/frontend-production.js?rand=" (Math/random))}]
     (if false
       [:script {:type "text/javascript" :src "/js/bin-debug/main.js"}]
       (list
        [:script {:type "text/javascript" :src "/js/vendor/react-0.11.2.js"}]
        [:script {:type "text/javascript" :src "/cljs/out/goog/base.js"}]
        [:script {:type "text/javascript" :src "/cljs/out/frontend-dev.js"}]
        [:script {:type "text/javascript"}
         "goog.require(\"frontend.core\");"])))))

(defn app [view-data]
  (h/html (app* view-data)))
