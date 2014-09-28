(ns pc.views.content
  (:require [hiccup.core :as h]
            [pc.stefon]))

(defn layout [& content]
  [:html
   [:head
    [:title "Precursor - Mockups from the future"]]
   [:body
    ;;[:link.css-styles {:rel "stylesheet", :href "/css/bootstrap.min.css"}]
    ;;[:link.css-styles {:rel "stylesheet", :href "/css/styles.css"}]
    [:div.alerts-container]
    content
    (when true ;;(not= :prod #_(carica/config :env-name))
      ;;[:script (browser-connected-repl-js)]
      )]])

(defn app* []
  (layout
   [:input.history {:style "display:none;"}]
   [:div#player-container]
   [:div#app-container]
   [:div.debugger-container]
   [:div#app]
   [:link.css-styles {:rel "stylesheet", :href (pc.stefon/asset-path "css/app.css")}]
   (if (= (System/getenv "PRODUCTION") "true")
     [:script {:type "text/javascript" :src "/js/bin/main.js"}]
     (if false
       [:script {:type "text/javascript" :src "/js/bin-debug/main.js"}]
       (list
        [:script {:type "text/javascript" :src "/js/vendor/react-0.10.0.js"}]
        [:script {:type "text/javascript" :src "/cljs/out/goog/base.js"}]
        [:script {:type "text/javascript" :src "/cljs/out/frontend-dev.js"}]
        [:script {:type "text/javascript"}
         "goog.require(\"frontend.core\");"])))))

(defn app []
  (h/html (app*)))
