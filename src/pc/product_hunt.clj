(ns pc.product-hunt
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.core.memoize :as memo]
            [clojure.tools.logging :as log]
            [hiccup.core :as h]))

(def ph-comments
  [:i {:class "icon-ph-comments"}
   [:svg {:class "iconpile" :viewBox "0 0 100 100"}
    [:path {:class "fill-ph-comments" :d "M11.7,70C4.4,63.3,0,54.7,0,45.4C0,24.2,22.4,7.1,50,7.1 s50,17.1,50,38.3S77.6,83.6,50,83.6c-6.9,0-13.4-1.1-19.4-3C20.9,85.9,1.9,92.9,1.9,92.9S8.2,78.6,11.7,70z"}]]])

(def api-token "72790c16bb982b444377523ed715075101240cb3370f642def65551b39bf1270")

(defn get-post-info* [post-id]
  (-> (http/get (str "https://api.producthunt.com/v1/posts/" post-id)
                {:headers {"Authorization" (str "Bearer " api-token)
                           "Content-Type" "application/json"}
                 :throw-exceptions false
                 :socket-timeout 500
                 :conn-timeout 500})
    :body
    json/decode
    (get "post")))

(def get-post-info (memo/ttl get-post-info* :ttl/threshold (* 1000 60 10)))

(def fallback-info
  {11067 {"name" "Precursor",
          "votes_count" 341,
          "comments_count" 10,
          "tagline" "A real-time collaborative prototyping tool"
          "discussion_url" "http://www.producthunt.com/posts/precursor"
          "makers" [{"image_url" {"30px" "http://avatars-cdn.producthunt.com/106624/30?1430244521"}}]
          "votes" [{"user"
                    {"image_url" {"30px" "http://avatars-cdn.producthunt.com/7824/30?1430236722"}}}]}
   19567 {"name" "Precursor",
          "votes_count" 2,
          "comments_count" 1,
          "tagline" "We make prototyping and team collaboration quick and simple"
          "discussion_url" "http://www.producthunt.com/posts/precursor-for-teams"
          "makers" [{"image_url" {"30px" "http://avatars-cdn.producthunt.com/106624/30?1430244521"}}
                    {"image_url" {"30px" "http://avatars-cdn.producthunt.com/106635/30?1430312595"}}]}})

(defn post-info [post-id]
  (try
    (get-post-info post-id)
    (catch Exception e
      (log/infof "Error getting post info for %s" post-id)
      (fallback-info post-id))))

(defn product-hunt-component [post-id]
  (let [info (post-info post-id)
        make-link (fn [content]
                    [:a {:href (get info "discussion_url")}
                     content])]
    (h/html
     [:div.product-hunt-card
      [:a.ph-big-link {:href (get info "discussion_url")}]
      [:div.ph-upvote
       (make-link (get info "votes_count"))]
      [:div.ph-info
       [:div.ph-title (make-link (get info "name"))]
       [:div.ph-tagline (get info "tagline")]]
      [:div.ph-people
       [:div.ph-makers
        (for [maker (get info "makers")]
          [:div.ph-maker
           [:img.ph-avatar {:src (get-in maker ["image_url" "30px"])}]])]
       (when-let [voter (some-> info (get "votes") first (get "user"))]
         [:div.ph-hunter
          [:img.avatar {:src (get-in voter ["image_url" "30px"])}]])]
      [:div.ph-comments-icon ph-comments]
      [:div.ph-comments-count (make-link (get info "comments_count"))]])))
