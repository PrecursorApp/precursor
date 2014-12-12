(ns pc.views.blog.common)

(defn tweet [name id & {:keys [no-parent]}]
  (list
   [:script {:charset "utf-8", :src "//platform.twitter.com/widgets.js", :async "async"}]
   [:blockquote.twitter-tweet {:data-conversation (when no-parent "none")}
    [:a {:href (str "https://twitter.com/" name "/status/" id)
         :data-loading-tweet "Loading tweet..."
         :data-failed-tweet " failed. View on Twitter."}]]))
