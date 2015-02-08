(ns pc.views.content
  (:require [cheshire.core :as json]
            [hiccup.core :as h]
            [pc.assets]
            [pc.datomic.schema :as schema]
            [pc.utils :as utils]
            [pc.views.common :as common :refer (cdn-path)]
            [pc.views.scripts :as scripts]
            [pc.views.email-landing :as email-landing]
            [pc.profile :refer (prod-assets?)])
  (:import java.util.UUID))

(defn embed-json-in-head
  "Safely embed json in the header. json-string will be parsed and defined as window.`variable-name`.
  Replaces the unsafe <script>variable-name = (->json clojure-map)</script>.
  HTML escapes the json-string, embeds it in a tag's value, then lets the browser's html parser
  unescape the json-string, runs it through JSON.parse and defines it as window.`variable-name`."
  [variable-name json-string]
  (let [id (-> (java.util.UUID/randomUUID))]
    (h/html
     [:meta {:id id
             ;; hiccup will escape the json-string
             :content json-string}]
     [:script {:type "text/javascript"}
      ;; getElementById(id).content will unescape the json-string
      (format "%s = JSON.parse(document.getElementById('%s').content);"
              variable-name
              id)])))

(defn escape-entity [entity]
  (reduce (fn [entity [k v]]
            (assoc entity k (if (schema/unescaped? k)
                              (h/h v)
                              v)))
          entity entity))

(defn serialize-entities [entities]
  (pr-str (mapv escape-entity entities)))

(defn layout [view-data & content]
  [:html
   [:head
    [:title "Precursor—fast prototyping web app, makes collaboration easy."]
    [:meta {:charset    "utf-8"}]
    [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge"}]

    [:meta {:name       "description"     :content "Your wireframe should be easy to share with any developer on your team. Design fast with iPhone and iPad collaboration. Precursor is productive prototyping."}]

    [:meta {:name "viewport"                              :content "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"}]
    [:meta {:name "apple-touch-fullscreen"                :content "yes"}]
    [:meta {:name "apple-mobile-web-app-capable"          :content "yes"}]
    [:meta {:name "apple-mobile-web-app-status-bar-style" :content "black"}]
    [:meta {:name "apple-mobile-web-app-title"            :content "Precursor"}]
    [:meta {:name "format-detection"                      :content "telephone=no"}]

    ;; TODO make the rest of these startup images
    [:link {:href (cdn-path "/img/750x1294.png") :rel "apple-touch-startup-image" :media "(device-width: 375px) and (device-height: 667px) and (orientation:  portrait) and (-webkit-device-pixel-ratio: 2)"}]
    [:link {:href (cdn-path "/img/1242x2148.png") :rel "apple-touch-startup-image" :media "(device-width: 414px) and (device-height: 736px) and (orientation:  portrait) and (-webkit-device-pixel-ratio: 3)"}]
    [:link {:href (cdn-path "/img/2208x1182.png") :rel "apple-touch-startup-image" :media "(device-width: 414px) and (device-height: 736px) and (orientation: landscape) and (-webkit-device-pixel-ratio: 3)"}]

    [:meta {:name "og:card"         :content "summary"}]
    [:meta {:name "og:description"  :content "Precursor lets you prototype product design wireframes with a fast and simple web app."}]
    [:meta {:name "og:image"        :content (cdn-path "/img/precursor-logo.png")}]
    [:meta {:name "og:image:width"  :content "1200"}]
    [:meta {:name "og:image:height" :content "1200"}]
    [:meta {:name "og:site_name"    :content "Precursor"}]
    [:meta {:name "og:title"        :content "Fast prototyping web app, makes collaboration easy."}]
    [:meta {:name "og:type"         :content "website"}]
    [:meta {:name "og:url"          :content "https://prcrsr.com/"}]

    [:meta {:name "twitter:card"         :content "summary_large_image"}]
    [:meta {:name "twitter:description"  :content "Precursor lets you prototype product design wireframes with a fast and simple web app."}]
    [:meta {:name "twitter:image:src"    :content (cdn-path "/img/precursor-logo.png")}]
    [:meta {:name "twitter:image:width"  :content "1200"}]
    [:meta {:name "twitter:image:height" :content "1200"}]
    [:meta {:name "twitter:site"         :content "@prcrsr_app"}]
    [:meta {:name "twitter:site:id"      :content "2900854766"}]
    [:meta {:name "twitter:title"        :content "Fast prototyping web app, makes collaboration easy."}]
    [:meta {:name "twitter:url"          :content "https://prcrsr.com/"}]

    [:link {:rel "icon"             :href (cdn-path "/favicon.ico")}]
    [:link {:rel "apple-touch-icon" :href (cdn-path "/img/apple-touch-icon.png")}]
    [:link {:rel "stylesheet"       :href (pc.assets/asset-path "/css/app.css")}]
    [:link {:rel "stylesheet"       :href "https://fonts.googleapis.com/css?family=Roboto:500,900,100,300,700,400"}]

    [:style "#om-app{min-height:100vh;background-size:10px 10px,10px 10px,100px 100px,100px 100px;background-color:rgba(51,51,51,1);background-image:linear-gradient(to bottom,rgba(85,85,85,.25)10%,rgba(85,85,85,0)10%),linear-gradient(to right,rgba(85,85,85,.25)10%,rgba(85,85,85,0)10%),linear-gradient(to bottom,rgba(85,85,85,.57)1%,rgba(85,85,85,0)1%),linear-gradient(to right,rgba(85,85,85,.57)1%,rgba(85,85,85,0)1%);}#om-app:active{cursor:wait;}"]

    (embed-json-in-head "window.Precursor"
                        (json/encode (-> view-data
                                       (utils/update-when-in [:initial-entities] serialize-entities)
                                       (utils/update-when-in [:cust] #(-> % escape-entity pr-str))
                                       (assoc :cdn-base-url (common/cdn-base-url)))))
    (when (prod-assets?)
      scripts/google-analytics)
    (scripts/rollbar (pc.profile/env) (pc.assets/asset-manifest-version))
    (scripts/mixpanel)]
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
   [:div#om-app]
   (if (prod-assets?)
     [:script {:type "text/javascript" :crossorigin "anonymous" :src (pc.assets/asset-path "/cljs/production/frontend.js")}]
     (if false
       [:script {:type "text/javascript" :src "/cljs/production/frontend.js"}]
       (list
        [:script {:type "text/javascript" :src "/js/vendor/react-0.12.2.js"}]
        [:script {:type "text/javascript" :src "/cljs/out/goog/base.js"}]
        [:script {:type "text/javascript" :src "/cljs/out/frontend-dev.js"}]
        [:script {:type "text/javascript"}
         "goog.require(\"frontend.core\");"
         "goog.require(\"frontend.dev\");"])))))

(defn app [view-data]
  (h/html (app* view-data)))

(defn interesting* [doc-ids]
  [:div.interesting
   (if-not (seq doc-ids)
     [:p "Nothing interesting today"])
   (for [doc-id doc-ids]
     [:div.doc-preview
      [:a {:href (str "/document/" doc-id)}
       [:img {:src (str "/document/" doc-id ".svg")}]]
      [:a {:href (str "/document/" doc-id)} doc-id]])])

(defn interesting [doc-ids]
  (h/html (layout {} (interesting* (reverse (sort doc-ids))))))

(defn email-welcome [template-name {:keys [CSRFToken]}]
  (h/html (layout {} (email-landing/email-landing template-name CSRFToken))))
