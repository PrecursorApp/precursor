(ns pc.views.blog.prototyping-tshirt-lobanovskiy
  (:require [ring.middleware.anti-forgery :refer (wrap-anti-forgery)]
            [pc.http.urls :as urls]
            [pc.views.email :as email]
            [pc.profile :as profile]
            [pc.views.common :refer (cdn-path external-cdn-path)]
            [pc.views.blog.common :as common]))

(defn prototyping-tshirt-lobanovskiy []
  {:title "I'd wear that."
   :blurb ""
   :author "Danny"
   :image (external-cdn-path "/blog/ideas-are-made-with-precursor/lobanovskiy.gif")
   :body
   (list

    [:article
     [:p "Got something interesting, made with " [:a {:href "/new" :title "Make something."} "Precursor"] "? "]
     [:p "Tweet " [:a {:href "https://twitter.com/PrecursorApp"} "@PrecursorApp"] " and include #madewithprecursor. "
         " I'll send you a one-of-a-kind shirt for free, if we feature it next time! "]]

    [:figure
     [:a {:href "/blog/ideas-are-made-with-precursor"
          :title "Read full article."}
      [:img {:src (external-cdn-path "/blog/ideas-are-made-with-precursor/eddie-shirt.png")}]]]

    [:article.blogpost-author
     [:p
      [:a {:href "/blog/ideas-are-made-with-precursor"
           :title "Read full article."}
       "Learn more."]]]

    )})


