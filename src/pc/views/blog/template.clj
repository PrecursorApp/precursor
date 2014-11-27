(ns pc.views.blog.template)

;; To make a new post, copy this template to a new file, then
;; add pc.views.blog.your-post-name to pc.views.blog, then add the
;; your-post-name to the end of slugs in pc.views.blog.
;; Whatever you set as your-post-name will be the url.

(defn template []
  {:title "Template post, not a real post!"
   :body
   [:div [:p ""]]})
