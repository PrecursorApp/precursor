(ns pc.views.blog
  (:require [hiccup.core :refer [html]]
            [pc.views.content]
            [pc.views.blog.interactive-layers]
            [pc.util.http :as http-util]))

(defn post-fn [slug]
  (ns-resolve (symbol (str "pc.views.blog." slug))
              (symbol slug)))

(defn post-url [slug]
  (str (assoc (http-util/self-base-url)
         :path (str "/blog/" slug))))

(def slugs
  "Sorted array of slugs, assumes the post content can be found in the
   function returned by post-fn"
  ["interactive-layers"])

(defn post-exists? [slug]
  (not= -1 (.indexOf slugs slug)))

(defn overview []
  [:div
   [:h3 "Recent posts"]
   (for [slug slugs]
     [:p
      [:a {:href (post-url slug)}
       (:title ((post-fn slug)))]])])

(defn single-post [slug]
  (let [post ((post-fn slug))]
    [:div
     [:h3 (:title post)]
     (:body post)]))

(defn render-page [slug]
  (html (pc.views.content/layout
         {}
         [:div.blog
          (if (post-exists? slug)
            (single-post slug)
            (overview))])))
