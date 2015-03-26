(ns pc.views.blog.common
  (:require [pc.views.common :refer (cdn-path)]))

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
