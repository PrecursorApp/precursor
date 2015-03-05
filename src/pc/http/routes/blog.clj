(ns pc.http.routes.blog
  (:require [defpage.core :as defpage :refer (defpage)]
            [pc.views.blog :as blog]))

;; TODO: could we serve the blog on a separate port so that it acts like its own app?

(defpage overview "/blog" [req]
  {:status 200
   :body (blog/render-page nil)})

(defpage post "/blog/:slug" [req]
  (let [slug (-> req :params :slug)]
    {:status 200
     :body (blog/render-page slug)}))

(def app (defpage/collect-routes))
