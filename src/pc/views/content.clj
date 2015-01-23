(ns pc.views.content
  (:require [cheshire.core :as json]
            [hiccup.core :as h]
            [pc.assets]
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

(defn layout [view-data & content]
  [:html
   [:head
    [:title "Precursor is a fast web app for prototyping — make simple stuff."]
    [:meta {:charset    "utf-8"}]
    [:meta {:http-equiv "X-UA-Compatible"     :content "IE=edge"}]
    [:meta {:name       "description"         :content "Your wireframe should be easy to share with any developer on your team. Design fast with iPhone and iPad collaboration. Precursor is productive prototyping."}]
    [:meta {:name       "viewport"            :content "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"}]

    ;; TODO finish ogs
    ;; [:meta {:name       "og:card"        :content "summary"}]
    ;; [:meta {:name       "og:site"        :content "@prcrsr_app"}]
    ;; [:meta {:name       "og:title"       :content "Precursor is a fast web app for prototyping."}]
    ;; [:meta {:name       "og:description" :content "You can prototype product design wireframes with a fast and simple web app."}]
    ;; [:meta {:name       "og:image"       :content ""}]
    ;; [:meta {:name       "og:url"         :content "https://prcrsr.com/"}]

    [:meta {:name       "twitter:card"        :content "summary"}]
    [:meta {:name       "twitter:site"        :content "@prcrsr_app"}]
    [:meta {:name       "twitter:title"       :content "Precursor is a fast web app for prototyping."}]
    [:meta {:name       "twitter:description" :content "You can prototype product design wireframes with a fast and simple web app."}]
    [:meta {:name       "twitter:image"       :content ""}] ; TODO
    [:meta {:name       "twitter:url"         :content "https://prcrsr.com/"}]

    [:link {:rel "icon" :href "/favicon.ico" :type "image/ico"}]
    [:link {:rel        "apple-touch-icon"    :href    "/apple-touch-icon.png"}]
    [:link {:rel        "stylesheet"          :href    (pc.assets/asset-path "/css/app.css")}]
    [:link {:rel        "stylesheet"          :href    "https://fonts.googleapis.com/css?family=Roboto:500,900,100,300,700,400"}]
    (embed-json-in-head "window.Precursor" (json/encode view-data))
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
       [:script {:type "text/javascript" :src "/js/bin-debug/main.js"}]
       (list
        [:script {:type "text/javascript" :src "/js/vendor/react-0.12.2.js"}]
        [:script {:type "text/javascript" :src "/cljs/out/goog/base.js"}]
        [:script {:type "text/javascript" :src "/cljs/out/frontend-dev.js"}]
        [:script {:type "text/javascript"}
         "goog.require(\"frontend.core\");"])))))

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
