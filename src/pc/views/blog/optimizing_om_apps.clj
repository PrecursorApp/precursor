(ns pc.views.blog.optimizing-om-apps
  (:require [pc.views.blog.common :as common]
            [pc.views.common :refer (cdn-path)]))

(def will-mount-code-block
"(defn wrap-will-mount
  \"Tracks last call time of componentWillMount for each component, then calls
   the original componentWillMount.\"
  [f]
  (fn []
    (this-as this
      (swap! component-stats update-in [(utils/react-id this)]
             merge {:display-name ((aget this \"getDisplayName\"))
                    :last-will-mount (time/now)})
      (.call f this))))")

(def did-mount-code-block
"

(defn wrap-did-mount
  \"Tracks last call time of componentDidMount for each component and updates
   the render times (using start time provided by wrap-will-mount), then
   calls the original componentDidMount.\"
  [f]
  (fn []
    (this-as this
      (swap! component-stats update-in [(utils/react-id this)]
             (fn [stats]
               (let [now (time/now)]
                 (-> stats
                   (assoc :last-did-mount now)
                   (update-in [:mount-ms] (fnil conj [])
                              (if (and (:last-will-mount stats)
                                       (time/after? now (:last-will-mount stats)))
                                (time/in-millis (time/interval (:last-will-mount stats)
                                                               now))
                                0))))))
      (.call f this))))")

(defn optimizing-om-apps []
  {:title "Optimizing Om Apps"
   :blurb "Our front end is built with Om and React. Both have excellent performance right out of the box, but we needed more insight."
   :author "Daniel"
   :body
   (list
    [:article
     [:p "Performance is very important for a prototyping tool, particularly one built on the web. This forces me to spend a lot of time thinking about browser performance for "
      [:a {:href "https://precursorapp.com"} "Precursor"]
      ". Our goal at Precursor is to make collaborating on ideas effortless. If things don't behave the way users expect, there's a risk of pushing them backwards—retreating towards alternatives that are more familiar, like pen and paper or whiteboards."]
     [:p "Our front end is build with "
      [:a {:href "https://github.com/omcljs/om"} "Om"]
      " and "
      [:a {:href "https://github.com/facebook/react"} "React"]
      "; both come with excellent performance right out of the box. React is well-known for minimizing the amount of slow DOM updates with their virtual DOM diffing. Om improves on that by "
      [:a {:href "https://swannodette.github.io/2013/12/17/the-future-of-javascript-mvcs/"} "leveraging fast comparisons of Closure’s persistent data structures"]
      "."]

     [:p "As good as they are, the defaults weren't good enough for us. I noticed that Precursor felt sluggish when too many people were drawing in the same document together, and when drawing on mobile browsers. I traced most of these problems to components rendering unnecessarily. For example, the chat component would re-render every time a new drawing was created."]

     [:p "To get a sense for performance bottlenecks, you generally want to see:"]

     [:p
      [:ul
       [:li "How many times a component is mounted/unmounted"]
       [:li "How many times a component is rendered (to determine when it’s being rendered unnecessarily)"]
       [:li "How long each component takes to render"]]]

     [:p "I built some instrumentation tools to display this information in an actionable format while interacting with the app. These tools have been invaluable while optimizing Precursor, and we've decided to open-source them so anyone can include them in their own Om projects."]

     [:p "I'm calling the library Om-i (pronounced “Oh, my!”), which is short for Om-instrumentation. You can find "
      [:a {:href "https://github.com/PrecursorApp/Om-i"}
       "Om-i on GitHub"]
      " and you can also play with a live version on "
      [:a {:href "https://precursorapp.com"}
       "Precursor"]
      " by hitting Ctrl+Shift+Alt+J."]]

    ;; n.b. this image is only stored in the CDN, it's not available locally
    (common/demo (cdn-path "/blog/optimizing-om-apps/instrumentation-frame.png") "https://dtwdl3ecuoduc.cloudfront.net/om-i/instrumentation.gif")

    [:article
     [:h3 "How it works"]
     [:p "Om lets you specify custom handlers for React's component lifecycle methods. First, we track mount times by wrapping Om's default componentWillMount and componentDidMount methods. Then in componentWillMount, we associate the start time with the component's React id, and calculate the mount time when componentDidMount fires. We also do the same with the update lifecycle methods to get render times."]
     [:code.block
      [:span will-mount-code-block]
      [:span did-mount-code-block]]
     [:p "Every mount and update gets stored to keep track of average and maximum render times."]]

    [:article.blogpost-footnote
     [:p
      "Thanks to "
      [:a {:href "https://twitter.com/sgrove"}
       "Sean Grove"]
      " for reading drafts of this."]
     [:p
      "Based on previous work, "
      [:a {:href "https://dwwoelfel.github.io/instrumenting-om-components.html"} "Instrumenting Om Components"]
      "."]])})
