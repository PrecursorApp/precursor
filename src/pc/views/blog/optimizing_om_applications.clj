(ns pc.views.blog.optimizing-om-applications)

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
"(defn wrap-did-mount
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

(defn optimizing-om-applications []
  {:title "Optimizing Om applications"
   :blurb "We've released a new library for Om that we use to optimize Precursor."
   :author "Daniel"
   :body
   (list
    [:article
     [:p "Performance is very important for a prototyping tool, particularly one built for the web. This forces me to spend a lot of time thinking about browser performance for "
      [:a {:href "https://precursorapp.com"} "Precursor"]
      ". Our goal at Precursor is to make collaborating on ideas effortless. If things don't behave the way our users expect, we run the risk of pushing them backwards—retreating towards alternatives that are more familiar, like pen and paper or whiteboards."]
     [:p "We built our front end with "
      [:a {:href "https://github.com/omcljs/om"} "Om"]
      " and "
      [:a {:href "https://github.com/facebook/react"} "React"]
      "; both come with excellent performance right out of the box. React is well-known for minimizing the amount of slow DOM updates with their virtual DOM diffing. Om improves on that by "
      [:a {:href "https://swannodette.github.io/2013/12/17/the-future-of-javascript-mvcs/"} "leveraging fast comparisons of Clojure’s persistent data structures"]
      "."]

     [:p "Unfortunately, the defaults weren't good enough for us. I noticed that Precursor felt sluggish when too many people were drawing in the same document at the same time, and when the app was used on mobile browsers. We traced most of these problems to components rendering unnecessarily. For example, the chat component would re-render every time a new drawing was created."]

     [:p "I built some instrumentation tools to help identify components that get passed too much data or are slow to render. These tools have been invaluable while optimizing Precursor, and we've decided to open-source them so anyone can include them in their own Om projects."]

     [:p "I'm calling the library Om-i (pronounced “Oh, my!”), which is short for Om-instrumentation. You can find "
      [:a {:href "https://github.com/PrecursorApp/Om-i"}
       "Om-i on GitHub"]
      " and you can also play with a live version on "
      [:a {:href "https://precursorapp.com"}
       "Precursor"]
      " by hitting Ctrl+Shift+Alt+J."]]

    [:article
     [:h3 "How it works"]
     [:p "Om lets you specify custom handlers for React's component lifecycle methods. First, we track mount times by wrapping Om's default componentWillMount and componentDidMount methods. Then in componentWillMount, we associate the start time with the component's React id, and calculate the mount time when componentDidMount fires. We also do the same with the update lifecycle methods to get render times."]
     [:p
      [:code [:pre will-mount-code-block]]
      [:code [:pre did-mount-code-block]]]
     [:p "Every mount and update gets stored to keep track of average and maximum render times."]]

    ;; n.b. that this image is only stored in the CDN, it's not available locally
    [:figure [:img {:src "https://dtwdl3ecuoduc.cloudfront.net/om-i/om-i-demo.gif"}]]

    [:article
     [:p
      "This library was based on previous work, "
      [:a {:href "https://dwwoelfel.github.io/instrumenting-om-components.html"} "Instrumenting Om Components"]
      "."]])})
