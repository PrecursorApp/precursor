(ns pc.views.blog.common
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [pc.views.common :refer (cdn-path)])
  (:import [java.util UUID]))

(defn tweet [name id & {:keys [no-parent]}]
  [:div.twitter-card
   [:script {:charset "utf-8", :src "//platform.twitter.com/widgets.js", :async "async"}]
   [:blockquote.twitter-tweet {:data-conversation (when no-parent "none")
                               :data-cards "hidden"}
    [:a {:href (str "https://twitter.com/" name "/status/" id)
         :data-loading-tweet "Loading tweet..."
         :data-failed-tweet " failed. View on Twitter."}]]])

(defn demo [placeholder gif & {:keys [caption]}]
  [:figure.play-gif {:alt "demo"
                     :onmouseover (format "this.getElementsByTagName('img')[0].src = '%s'" gif)
                     :ontouchstart (format "this.getElementsByTagName('img')[0].src = '%s'" gif)
                     :onmouseout (format "this.getElementsByTagName('img')[0].src = '%s'" placeholder)
                     :ontouchend (format "this.getElementsByTagName('img')[0].src = '%s'" placeholder)}
   [:a (when caption
         {:data-caption caption})
    [:img {:src placeholder}]]])

(defn demo-with-blank-canvas [gif caption]
  (demo (cdn-path "/blog/private-docs-early-access/canvas.png") gif :caption caption))

(def logo-dribbble
  [:i {:class "icon-dribbble"}
   [:svg {:class "iconpile" :viewBox "0 0 100 100"}
    [:path {:class "fill-dribbble" :d "M50,99.9C22.4,99.9,0,77.5,0,50C0,22.5,22.4,0.1,50,0.1 c27.6,0,50,22.4,50,49.9C100,77.5,77.6,99.9,50,99.9L50,99.9z M92.2,56.8c-1.5-0.5-13.2-4-26.6-1.8c5.6,15.3,7.9,27.8,8.3,30.4 C83.4,79,90.3,68.7,92.2,56.8L92.2,56.8z M66.7,89.3C66,85.6,63.6,72.5,57.6,57c-0.1,0-0.2,0.1-0.3,0.1 c-24.1,8.4-32.7,25.1-33.5,26.6c7.2,5.6,16.3,9,26.2,9C55.9,92.7,61.6,91.5,66.7,89.3L66.7,89.3z M18.3,78.6 c1-1.7,12.7-21,34.7-28.1c0.6-0.2,1.1-0.3,1.7-0.5c-1.1-2.4-2.2-4.8-3.5-7.2c-21.3,6.4-42,6.1-43.9,6.1c0,0.4,0,0.9,0,1.3 C7.3,61,11.5,71,18.3,78.6L18.3,78.6z M8.2,41.3c1.9,0,19.5,0.1,39.5-5.2C40.6,23.6,33,13,31.8,11.5C19.9,17.1,11,28.1,8.2,41.3 L8.2,41.3z M40,8.6c1.2,1.6,8.9,12.1,15.9,25c15.2-5.7,21.6-14.3,22.4-15.4C70.8,11.5,60.9,7.4,50,7.4C46.6,7.4,43.2,7.8,40,8.6 L40,8.6z M83.1,23.1c-0.9,1.2-8.1,10.4-23.8,16.8c1,2,1.9,4.1,2.8,6.2c0.3,0.7,0.6,1.5,0.9,2.2c14.2-1.8,28.3,1.1,29.7,1.4 C92.6,39.6,89,30.4,83.1,23.1L83.1,23.1z"}]]])

(def icon-user
  [:i {:class "icon-user"}
   [:svg {:class "iconpile" :viewBox "0 0 100 100"}
    [:path {:class "stroke-user" :d "M50,5 c11.8,0,21.3,9.5,21.3,21.3S61.8,47.6,50,47.6s-21.3-9.5-21.3-21.3S38.2,5,50,5z M24.8,45.9C17,52.4,12,64.1,12,71.9 C12,86.4,29,95,50,95c21,0,38-8.6,38-23.1c0-7.8-4.9-19.5-12.7-26"}]]])

(defn dribbble-card-script
  "Script that will populate the dribbble card with its attributes"
  [class-map username]
  [:script {:type "text/javascript"}
   (format
    "
(function () {
  var classMap = JSON.parse('%s');
  function handleReq () {
    var fields = JSON.parse(this.responseText);
    for (var key in fields) {
      if (fields.hasOwnProperty(key)) {
        if (classMap[key]) {
          var nodes = document.getElementsByClassName(classMap[key]);
          for (var i = 0; i < nodes.length; i++) {
            var node = nodes[i];
            var field = fields[key];
            if (subprop = node.getAttribute('data-subprop')) {
              for (k of subprop.split(',')) {
                field = field[k];
              }
            }
            node[node.getAttribute('data-attr')] = field;
          }
        }
      }
    }
  }
  var oReq = new XMLHttpRequest();
  oReq.onload = handleReq;
  oReq.open(\"get\", \"/api/v1/dribbble/users/%s\", true);
  oReq.send();
})();
"
    (json/encode class-map) username)])

(defn dribbble-attrs
  "class-map: top-level dribbble API response keys to auto-generated class.
   kstrs: dribbble API key, or seq of keys into the response map
   attr: js node attribute that will be set to the value of (get-in api-resp kstrs)
   extra-attrs: additional html-attrs (e.g. :width 20)"
  [class-map kstrs attr & {:as extra-attrs}]
  (let [class-key (if (coll? kstrs)
                    (first kstrs)
                    kstrs)]
    (merge extra-attrs
           {:class (str (:class extra-attrs) " " (get class-map class-key))
            :data-attr attr}
           (when (coll? kstrs)
             {:data-subprop (str/join "," (rest kstrs))}))))

(defn dribbble-card [username]
  (let [props ["comments_received_count" "bio" "shots_count" "can_upload_shot" "followings_count"
               "followers_url" "likes_received_count" "avatar_url" "username" "buckets_count"
               "pro" "id" "projects_url" "name" "likes_url" "location" "buckets_url" "updated_at"
               "html_url" "teams_count" "links" "likes_count" "shots_url" "following_url" "type"
               "created_at" "teams_url" "followers_count" "rebounds_received_count" "projects_count"]
        class-map (zipmap props (repeatedly #(str (UUID/randomUUID))))
        profile-link (dribbble-attrs class-map "html_url" "href")]
    [:div#vendor.dribbble-card
     (dribbble-card-script class-map username)
     [:div.dribbble-head
      [:div.dribbble-stats
       [:div.dribbble-stat
        [:strong (dribbble-attrs class-map "shots_count" "innerText")]
        [:span " Shots"]]
       [:div.dribbble-stat
        [:strong (dribbble-attrs class-map "followers_count" "innerText")]
        [:span " Followers"]]]
      [:div.dribbble-photo
       [:a profile-link
        [:img.dribbble-avatar (dribbble-attrs class-map "avatar_url" "src")]]]
      [:div.dribbble-follow
       [:a.dribbble-button profile-link
        logo-dribbble
        [:strong "Follow"]]]]
     [:div.dribbble-body
      [:div.dribbble-info
       [:a.dribbble-name profile-link
        [:strong (dribbble-attrs class-map "name" "innerText")]]]
      [:div.dribbble-link
       [:a.dribbble-site (dribbble-attrs class-map ["links" "web"] "href")
        [:span (dribbble-attrs class-map ["links" "web"] "innerText")]]]]]))

(defn card-maker [username]
  (let [props ["comments_received_count" "bio" "shots_count" "can_upload_shot" "followings_count"
               "followers_url" "likes_received_count" "avatar_url" "username" "buckets_count"
               "pro" "id" "projects_url" "name" "likes_url" "location" "buckets_url" "updated_at"
               "html_url" "teams_count" "links" "likes_count" "shots_url" "following_url" "type"
               "created_at" "teams_url" "followers_count" "rebounds_received_count" "projects_count"]
        class-map (zipmap props (repeatedly #(str (UUID/randomUUID))))
        profile-link (dribbble-attrs class-map "html_url" "href")]
    [:div.card-maker.free-border.card-maker-dribbble
     (dribbble-card-script class-map username)
     [:div.card-maker-head.free-border
      [:div.card-top.free-border
       [:div.card-stat
        [:span (dribbble-attrs class-map "shots_count" "innerText")]
        [:span " shots"]]
       [:div.card-stat
        [:span (dribbble-attrs class-map "followers_count" "innerText")]
        [:span " followers"]]]
      [:div.card-photo
       [:a profile-link
        [:img.card-avatar (dribbble-attrs class-map "avatar_url" "src")]]]
      [:div.card-top.free-border
       [:a.card-follow profile-link
        logo-dribbble
        [:span "Follow"]]]]
     [:div.card-maker-body
      [:div.card-name (dribbble-attrs class-map "name" "innerText")]
      [:div.card-link
       [:a profile-link
        [:span (str "@" username)]]]]]))

(defn card-doc [username document]
  (let [animated    (cdn-path (str "/blog/ideas-are-made-with-precursor/" username ".gif"))
        placeholder (cdn-path (str "/blog/ideas-are-made-with-precursor/" username "-placeholder.gif"))
        swap-img    "this.getElementsByTagName('img')[0].src = '%s'"]
    [:a.card-doc.free-border {:href (str "https://precursorapp.com" "/document/" document)
                              :target "_blank"
                              :onmouseover  (format swap-img animated)
                              :ontouchstart (format swap-img animated)
                              :onmouseout   (format swap-img placeholder)
                              :ontouchend   (format swap-img placeholder)}
     [:img {:src placeholder}]
     ; icon-user
     ; [:div.card-count "5"] ; holding off on viewer count
     ]))

(defn card [username document]
  [:div.card
   (card-doc username document)
   (card-maker username)])
