(ns pc.views.content
  (:require [cheshire.core :as json]
            [hiccup.core :as h]
            [pc.assets]
            [pc.datomic.schema :as schema]
            [pc.http.urls :as urls]
            [pc.utils :as utils]
            [pc.views.common :as common :refer (cdn-path)]
            [pc.views.scripts :as scripts]
            [pc.views.email-landing :as email-landing]
            [pc.profile :refer (prod-assets?)]
            [pc.stripe :as stripe])
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

(defn og-meta [prop content]
  [:meta {:name prop
          :property prop
          :content content}])

(defn layout [view-data & content]
  [:html
   [:head
    [:title (if-let [meta-title (:meta-title view-data)]
              (str meta-title " | Precursor")
              "Precursor&mdash;fast prototyping web app, makes collaboration easy.")]
    [:meta {:charset    "utf-8"}]
    [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge"}]

    [:meta {:name "description" :content (or (:meta-description view-data)
                                             "Your wireframe should be easy to share with any developer on your team. Design fast with iPhone and iPad collaboration. Precursor is productive prototyping.")}]

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

    (og-meta "twitter:card" "summary_large_image")
    (if-let [image-url (:meta-image view-data)]
      (og-meta "twitter:image:src" image-url)
      (list (og-meta "twitter:image:src" (cdn-path "/img/precursor-logo.png"))
            (og-meta "twitter:image:width" "1200")
            (og-meta "twitter:image:height" "1200")))
    (og-meta "twitter:site" "@PrecursorApp")
    (og-meta "twitter:site:id" "2900854766")
    (og-meta "twitter:title" (or (:meta-title view-data)
                                 "Fast prototyping web app, makes collaboration easy."))
    (og-meta "twitter:description" (or (:meta-description view-data)
                                       "Precursor lets you prototype product design wireframes with a fast and simple web app."))
    (og-meta "twitter:url" (or (:meta-url view-data)
                               (urls/root)))

    (og-meta "og:card" "summary")
    (if-let [image-url (:meta-image view-data)]
      (og-meta "og:image" image-url)
      (list (og-meta "og:image" (cdn-path "/img/precursor-logo.png"))
            (og-meta "og:image:width" "1200")
            (og-meta "og:image:height" "1200")))
    (og-meta "og:site_name" "Precursor")
    (og-meta "og:title" (or (:meta-title view-data)
                            "Fast prototyping web app, makes collaboration easy."))
    (og-meta "og:description" (or (:meta-description view-data)
                                  "Precursor lets you prototype product design wireframes with a fast and simple web app."))
    (og-meta "og:type" "website")
    (og-meta  "og:url" (or (:meta-url view-data)
                           (urls/root)))

    [:link {:rel "icon"             :href (cdn-path "/favicon.ico")}]
    [:link {:rel "apple-touch-icon" :href (cdn-path "/img/apple-touch-icon.png")}]
    [:link {:rel "stylesheet"       :href (pc.assets/asset-path "/css/app.css")}]
    [:link {:rel "stylesheet"       :href "https://fonts.googleapis.com/css?family=Roboto:500,900,100,300,700,400"}]

    [:style (common/head-style)]

    (embed-json-in-head "window.Precursor"
                        (json/encode (-> view-data
                                       (utils/update-when-in [:initial-entities] pr-str)
                                       (utils/update-when-in [:initial-issue-entities] pr-str)
                                       (utils/update-when-in [:cust] pr-str)
                                       (utils/update-when-in [:team] pr-str)
                                       (assoc :cdn-base-url (common/cdn-base-url)
                                              :manifest-version (pc.assets/asset-manifest-version)
                                              :page-start (java.util.Date.)
                                              :stripe-publishable-key (stripe/publishable-key)))))
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
        [:script {:type "text/javascript"} "window.Precursor['logging-enabled']=true"]
        [:script {:type "text/javascript" :src "/cljs/out/goog/base.js"}]
        [:script {:type "text/javascript" :src "/cljs/out/frontend-dev.js"}]
        [:script {:type "text/javascript"}
         "goog.require(\"frontend.core\");"
         "goog.require(\"frontend.dev\");"])))
   [:script {:type "text/javascript"}
    (format "rasterize = {}; rasterize.api_key = '%s';"
            (pc.profile/rasterize-key))]
   "<script async src=\"https://rasterize.io/rasterize.js\" />"))

(defn app [view-data]
  (h/html (app* view-data)))

(defn email-welcome [template-name {:keys [CSRFToken]}]
  (h/html (layout {} (email-landing/email-landing template-name CSRFToken))))
