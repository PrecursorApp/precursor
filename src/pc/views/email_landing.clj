(ns pc.views.email-landing
  (:require [cheshire.core :as json]
            [hiccup.core :as h]
            [pc.views.scripts :as scripts]
            [pc.profile :refer (prod-assets?)]))

(def template->gif-url
  {"simple-tools"      "/email/simple-tools.gif"
   "team-chat"         "/email/team-chat.gif"
   "interactive-demo"  "/email/interactive-demo.gif"
   "this-email"        "/email/this-email.gif"
   "multi-duplication" "/email/multi-duplication.gif"})

(defn get-gif-url [template-name]
  (get template->gif-url template-name "/email/simple-tools.gif"))


(defn email-landing [template-name]
  [:div.email-landing
   [:a {:href "/"
        :title "Make something."}
    [:img {:src (get-gif-url template-name)}]]
   [:a.email-landing-button {:href "/"
                             :role "button"
                             :title "Make something."}
    "Make with Precursor"]])
