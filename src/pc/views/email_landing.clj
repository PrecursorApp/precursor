(ns pc.views.email-landing
  (:require [cheshire.core :as json]
            [hiccup.core :as h]
            [pc.views.scripts :as scripts]
            [pc.profile :refer (prod-assets?)]))

(def template->gif-url
  {"simple-tools" "/email/simple-tools.gif"})

(defn get-gif-url [template-name]
  (get template->gif-url template-name "/email/simple-tools.gif"))


(defn email-landing [template-name]
  [:div
   [:p "Welcome to Precursor"]
   [:p [:img {:src (get-gif-url template-name)}]]])
