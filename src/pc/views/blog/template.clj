(ns pc.views.blog.template)

;; To make a new post, copy this template to a new file, rename the
;; template fn below to something like your-post-name, then add
;; "your-post-name" to the end of slugs in pc.views.blog.
;; Whatever you set as your-post-name will be the slug after /blog/ in the url.

(defn template []
  {:title nil ;; replace the nil with a string like "This is a title"
   :blurb nil ;; replace the nil with a short string about this post
   :author nil ;; should be "Danny" or "Daniel", ask Daniel to add you to the list if you want to blog
   :body (list
          [:article
           [:p "opening paragraph"]]
          [:h3 "Heading"]
          [:article
           [:p "New paragraph"]]
          [:figure [:img {:src "/some-image.png"}]])})
