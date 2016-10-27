(ns pc.views.blog.precursor-is-now-open-source
  (:require [pc.views.blog.common :as common]
            [pc.views.common :refer (cdn-path external-cdn-path)]))

(defn precursor-is-now-open-source []
  {:title "Precursor is now open source. "
   :blurb "As Precursor becomes a community-lead product, we will work hard to ensure that it will retain its thoughtful roots in simplicity. "
   :author "Danny"
   :body
   (list
    [:article
     [:p "They say good things come to those who wait, and you’ve all been waiting for quite awhile now. "
         "As of today, we’re very excited to finally announce that "
         [:a {:href "https://precursorapp.com/home"} "Precursor"]
         " is officially open source software! "]
     [:p "We started Precursor with the goal of simplifying the early-stage product design process. "
         "We wanted to not only encourage team collaboration, but make it easier than ever. "
         "Precursor’s sole purpose has always been to get out of your way, and allow you to effortlessly bring your ideas to fruition. "]
     [:p "Many of you connected strongly to Precursor's vision, particularly its ability to keep you focused on your ideas. "
         "I think there's still many insights to be shared from its philosophy and architecture. "
         "I wanted to shine a light on how much complex overhead we subject ourselves to as designers and developers. "
         "Sometimes we need a reminder that simplicity in a workflow is a good thing. "]
     [:p "We’re proud of the progress Precursor made with ClojureScript, WebRTC, and React. "
         "And beyond a solid tech stack, we also placed heavy focused on the product’s user experience. "
         "Our development process allowed for a strong emphasis on design—with every color, line, menu, and animation being meticulously thought through. "]
     [:p "As Precursor becomes a community-lead product, we will work hard to ensure that it will retain its thoughtful roots in simplicity and expression. "
         "We hope to continue to see our product bring joy to our users, and to also see our users engage in advancing the product. "]
     [:p "We’re looking forward to realizing Precursor’s potential with you, and invite you to join us on "
         [:a {:href "https://github.com/precursorapp/precursor"} "GitHub"]
         "! "]]

    )})
