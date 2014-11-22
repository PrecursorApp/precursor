(ns pc.views.email-landing
  (:require [cheshire.core :as json]
            [hiccup.core :as h]
            [pc.views.scripts :as scripts]
            [pc.profile :refer (prod-assets?)]))


(defn email-landing [template-name]
  [:div "Welcome to Precursor"])
