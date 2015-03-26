(ns pc.views.blog.common
  (:require [cheshire.core :as json]
            [pc.views.common :refer (cdn-path)])
  (:import [java.util UUID]))

(defn tweet [name id & {:keys [no-parent]}]
  (list
   [:script {:charset "utf-8", :src "//platform.twitter.com/widgets.js", :async "async"}]
   [:blockquote.twitter-tweet {:data-conversation (when no-parent "none")}
    [:a {:href (str "https://twitter.com/" name "/status/" id)
         :data-loading-tweet "Loading tweet..."
         :data-failed-tweet " failed. View on Twitter."}]]))

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

(defn dribbble-card-script [class-map username]
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
            node[node.getAttribute('data-attr')] = fields[key];
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

(defn dribbble-attrs [class-map prop attr & {:as extra-attrs}]
  (merge extra-attrs
         {:class (str (:class extra-attrs) " " (get class-map prop))
          :data-attr attr}))

(defn dribbble-card [username]
  (let [props ["comments_received_count" "bio" "shots_count" "can_upload_shot" "followings_count"
               "followers_url" "likes_received_count" "avatar_url" "username" "buckets_count"
               "pro" "id" "projects_url" "name" "likes_url" "location" "buckets_url" "updated_at"
               "html_url" "teams_count" "links" "likes_count" "shots_url" "following_url" "type"
               "created_at" "teams_url" "followers_count" "rebounds_received_count" "projects_count"]
        class-map (zipmap props (repeatedly #(str (UUID/randomUUID))))]
    [:div {:class "dribbble-card"}
     (dribbble-card-script class-map username)
     [:img (dribbble-attrs class-map "avatar_url" "src")]
     [:div
      "Shots: "
      [:span (dribbble-attrs class-map "shots_count" "innerText"
                             :class "shots-count")]]
     [:div
      "Followers: "
      [:span (dribbble-attrs class-map "followers_count" "innerText"
                             :class "followers-count")]]]))
