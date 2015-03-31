(ns pc.views.blog.id-wear-that
  (:require [ring.middleware.anti-forgery :refer (wrap-anti-forgery)]
            [pc.http.urls :as urls]
            [pc.views.email :as email]
            [pc.profile :as profile]
            [pc.views.common :refer (cdn-path external-cdn-path)]
            [pc.views.blog.common :as common]))

(defn id-wear-that []
  {:title "I'd wear that."
   :blurb "Show us the interesting thing you made on Precursor and you could get a T-shirt with your design..."
   :author "Danny"
   :image (external-cdn-path "/blog/ideas-are-made-with-precursor/eddie-shirt.png")
   :body
   (list
    [:article
     [:p "Got something interesting made with "
      [:a {:href "/new" :title "Make something."} "Precursor"]
      "? "]
     [:p
      "Tweet your design and include the hashtag #madewithprecursor. "
      "We'll send you a one-of-a-kind shirt for free if we feature it! "]]

    [:figure
     [:a {:href "/blog/ideas-are-made-with-precursor"
          :title "Read full article."}
      [:img {:src (external-cdn-path "/blog/ideas-are-made-with-precursor/eddie-shirt.png")}]]]

    [:article.blogpost-author
     [:p
      [:a {:href "/blog/ideas-are-made-with-precursor"
           :title "Read full article."}
       "Learn more."]]])})
