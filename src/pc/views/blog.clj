(ns pc.views.blog
  (:require [hiccup.core :refer [html]]
            [clj-time.core :as time]
            [clj-time.format]
            [pc.http.urls :as urls]
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
  (str "/blog/" slug))

(defn display-in-overview? [{:keys [display-in-overview pub-date] :as slug-map}]
  (and display-in-overview
       (or (not pub-date)
           (time/after? (time/now) pub-date))))

(def slugs
  "Sorted array of slugs, assumes the post content can be found in the
   function returned by post-fn
   :unique-id is a stable id for the RSS feed, it can never change
   Add a :pub-date key to prevent the post from showing up in the
   overview until after the scheduled time. Posts will still be accessible
   to anyone with the direct URL. Time should be in the format Fri, 30 Jan 2015 01:12:00 -0800
   Be careful for daylight savings time!"
  [
   {:slug "id-wear-that"
    :unique-id "id-wear-that"
    :display-in-overview false}
   {:slug "ideas-are-made-with-precursor"
    :unique-id "ideas-are-made-with-precursor"
    :display-in-overview true
    :pub-date (clj-time.format/parse "Tue, 31 Mar 2015 09:00:00 -0800")}
   {:slug "clojure-is-a-product-design-tool"
    :unique-id "clojure-is-a-product-design-tool"
    :display-in-overview true
    ;; 9am dst
    :pub-date (clj-time.format/parse "Thu, 12 Mar 2015 08:00:00 -0800")}
   {:slug "optimizing-om-apps"
    :unique-id "optimizing-om-apps"
    :display-in-overview true
    :pub-date (clj-time.format/parse "Mon, 2 Mar 2015 14:00:00 -0800")}
   {:slug "blue-ocean-made-of-ink"
    :unique-id "blue-ocean-made-of-ink"
    :display-in-overview true
    :pub-date (clj-time.format/parse "Fri, 30 Jan 2015 08:00:00 -0800")}
   {:slug "private-docs-early-access"
    :unique-id "private-docs-early-access"
    :display-in-overview false}
   {:slug "product-hunt-wake-up-call"
    :unique-id "product-hunt-wake-up-call"
    :display-in-overview true
    :pub-date (clj-time.format/parse "Fri, 9 Jan 2015 08:00:00 -0800")}
   {:slug "interactive-layers"
    :unique-id "interactive-layers"
    :display-in-overview true
    :pub-date (clj-time.format/parse "Fri, 4 Dec 2014 08:00:00 -0800")}
   ])

(defn post-exists? [slug]
  (not= -1 (.indexOf (map :slug slugs) slug)))

(def logomark
  [:i {:class "icon-logomark"}
   [:svg {:class "iconpile" :viewBox "0 0 100 100"}
    [:path {:class "fill-logomark" :d "M43,100H29.5V39H43V100z M94,33.8C90.9,22,83.3,12.2,72.8,6.1C62.2,0,50-1.6,38.2,1.6 C26.5,4.7,16.6,12.3,10.6,22.8C4.5,33.3,2.9,45.6,6,57.4l1.7,6.4l12.7-3.4l-1.7-6.4c-4.6-17.2,5.6-35,22.9-39.6 c8.3-2.2,17.1-1.1,24.6,3.2c7.5,4.3,12.8,11.3,15.1,19.7c4.6,17.2-5.6,35-22.9,39.6L52,78.5l3.4,12.7l6.4-1.7 C86.1,83.1,100.5,58,94,33.8z"}]]])

(def twitter
  [:i {:class "icon-twitter"}
   [:svg {:class "iconpile" :viewBox "0 0 100 100"}
    [:path {:class "fill-twitter" :d "M100,19c-3.7,1.6-7.6,2.7-11.8,3.2c4.2-2.5,7.5-6.6,9-11.4c-4,2.4-8.4,4.1-13,5c-3.7-4-9.1-6.5-15-6.5 c-11.3,0-20.5,9.2-20.5,20.5c0,1.6,0.2,3.2,0.5,4.7c-17.1-0.9-32.2-9-42.3-21.4c-1.8,3-2.8,6.6-2.8,10.3c0,7.1,3.6,13.4,9.1,17.1 c-3.4-0.1-6.5-1-9.3-2.6c0,0.1,0,0.2,0,0.3c0,9.9,7.1,18.2,16.5,20.1c-1.7,0.5-3.5,0.7-5.4,0.7c-1.3,0-2.6-0.1-3.9-0.4 c2.6,8.2,10.2,14.1,19.2,14.2c-7,5.5-15.9,8.8-25.5,8.8c-1.7,0-3.3-0.1-4.9-0.3c9.1,5.8,19.9,9.2,31.4,9.2 c37.7,0,58.4-31.3,58.4-58.4c0-0.9,0-1.8-0.1-2.7C93.8,26.7,97.2,23.1,100,19z"}]]])

(def authors
  [{:name "Danny"
    :url "https://twitter.com/dannykingme"}
   {:name "Daniel"
    :url "https://twitter.com/DanielWoelfel"}])

(defn author-link [author-name]
  (if-let [author (first (filter #(= author-name (:name %)) authors))]
    [:a.blogroll-post-author {:href (:url author)}
     (:name author)]
    author-name))

(defn blog-head []
  [:article
   [:div.blog-head
    [:a.blog-head-logo {:href "/blog"
                        :title "Precursor Blog"}
     logomark]]])

(defn overview []
  [:div.blogroll
   (blog-head)
   [:article
    (for [slug (->> slugs (filter display-in-overview?) (map :slug))
          :let [{:keys [title blurb author] :as content} ((post-fn slug))]]
      [:div.blogroll-post
       [:a.blogroll-post-title {:href (post-url slug)}
        [:h3 title]]
       [:p blurb]
       [:p (author-link author)]])]])

(defn single-post [post]
  [:div.blogpost
   (blog-head)
   [:div.blogpost-title
    [:article
     [:h2 (:title post)]]]
   (:body post)])

(defn render-page [slug]
  (let [post (when (post-exists? slug)
               ((post-fn slug)))]
    (html (pc.views.content/layout
           {:meta-title (:title post)
            :meta-description (:blurb post)
            :meta-image (:image post)
            :meta-url (urls/blog-url slug)}
           [:div.page-blog
            [:div.nav.nav-head ; keep up to date with outer/nav-head
             [:a.nav-link.nav-logo    {:href "/"        :title "Precursor"} "Precursor"]
             [:a.nav-link.nav-home    {:href "/home"    :title "Home"}      "Home"]
             [:a.nav-link.nav-pricing {:href "/pricing" :title "Pricing"}   "Pricing"]
             [:a.nav-link.nav-blog    {:href "/blog"    :title "Blog"}      "Blog"]
             [:a.nav-link.nav-app     {:href "/new"     :title "Launch"}    "App"]]
            (if post
              (single-post post)
              (overview))
            [:div.nav.nav-foot ; keep up to date with outer/nav-foot
             [:a.nav-link.nav-logo    {:href "/"        :title "Precursor"} logomark]
             [:a.nav-link.nav-home    {:href "/home"    :title "Home"}      "Home"]
             [:a.nav-link.nav-pricing {:href "/pricing" :title "Pricing"}   "Pricing"]
             [:a.nav-link.nav-blog    {:href "/blog"    :title "Blog"}      "Blog"]
             [:a.nav-link.nav-app     {:href "/new"     :title "Launch"}    "App"]
             [:a.nav-link.nav-twitter {:href "https://twitter.com/PrecursorApp" :title "@PrecursorApp"} twitter]]]
           [:script {:type "text/javascript"}
            (format "mixpanel.track(\"View blog\", {blog_post: \"%s\"})" (hiccup.core/h (:title post "Overview")))]))))

(defn slug->rss [slug pub-date unique-id]
  (let [post ((post-fn slug))]
    [:item
     [:title (:title post)]
     [:link (urls/blog-url slug)]
     [:description (str "<![CDATA["
                        (html (:body post))
                        "]]>")]
     [:pubDate (clj-time.format/unparse (clj-time.format/formatters :rfc822) pub-date)]
     [:author (:author post)]
     [:guid {:isPermaLink "false"} unique-id]]))

(defn generate-rss []
  (->> [:rss {:version "2.0"
              :xmlns:dc "http://purl.org/dc/elements/1.1/"
              :xmlns:sy "http://purl.org/rss/1.0/modules/syndication/"}
        [:channel
         [:title "Precursor"]
         [:link (urls/blog-root)]
         [:description "Fast prototyping web app, makes collaboration easy."]
         (for [{:keys [slug pub-date unique-id]} (filter display-in-overview? slugs)]
           (slug->rss slug pub-date unique-id))]]
    (html)))
