(ns pc.views.blog
  (:require [hiccup.core :refer [html]]
            [pc.profile :as profile]
            [pc.views.content]
            [pc.util.http :as http-util]))

(defonce requires (atom #{}))

(defn post-ns [slug]
  (symbol (str "pc.views.blog." slug)))

;; This is probably a bad idea, but it seems to work pretty well.
(defn maybe-require [slug]
  (let [ns (post-ns slug)]
    (when-not (contains? @requires ns)
      (require ns)
      (swap! requires conj ns))))

(defn post-fn [slug]
  (maybe-require slug)
  (ns-resolve (post-ns slug)
              (symbol slug)))

(defn post-url [slug]
  (str (assoc (http-util/self-base-url)
         :path (str "/blog/" slug))))

(def slugs
  "Sorted array of slugs, assumes the post content can be found in the
   function returned by post-fn"
  ["instrumenting-om-components"
   "interactive-layers"])

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
