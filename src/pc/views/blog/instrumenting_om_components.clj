(ns pc.views.blog.instrumenting-om-components)

(defn instrumenting-om-components []
  {:title "Instrumenting Om Components"
   :body
   (list
    [:article
     [:p
      "One of the big challenges in using React.js and Om is understanding how the code you write "
      "impacts performance and rendering time. In most cases, you shouldn’t care – React’s diffing model and "
      [:a {:href "https://swannodette.github.io/2013/12/17/the-future-of-javascript-mvcs/"}
       "Om’s handling of immutable data structures"]
      " make hand-tuning mostly superfluous. There will always be places where the defaults are too slow and "
      "micro-optimizations are required for the sake of performance. In those cases, Om’s global concerns allow "
      "us to create tools to see the bottlenecks and understand the impact of our optimzations."]
     [:p "I’ll show you how CircleCI leverages Om’s new “descriptor” overrides to understand performance in its "
      [:a {:href "https://github.com/circleci/frontend"} "open-source frontend"] ". "
      [:a {:href "https://circleci.com"} "CircleCI"] " is a hosted Continuous Delivery platform used by teams that "
      "want state-of-the-art CI and deployment."]]

    [:article
     [:h3 "Visually indicating performance bottlenecks"]
     [:p "Before React.js, " [:a {:href "http://www.html5rocks.com/en/tutorials/speed/unnecessary-paints/"} "paint rectangles"]
      " were a great way to identify performance bottlenecks in your application. If your application "
      "was unnecessarily mutating the DOM, Chrome would helpfully show you a transparent overlay over the "
      "parts of the page that were modified."]
     [:p "Here’s an example from Circle’s old haml-coffee/knockout.js frontend. We noticed that the site "
      "was feeling a bit sluggish. The paint rectangles made it obvious that the problem was the sidebar"
      " being re-rendered even though the content stayed the same."]]

    [:figure [:video {:controls "", :src "/assets/video/paint-rectangles.mp4"}] " "]

    [:article
     [:p "React diffs against its virtual copy of the DOM before it writes to the actual DOM, so paint rectangles "
      "can’t show you where you’re rendering too much. The benefit of React’s global optimization is that we never "
      "make those slow DOM updates, but the cost is that we can’t use all of the browser’s built-in tools for debugging."]
     [:p "My first approach to the problem was to hack the paint rectangles back into the page. I had each "
      "component generate a random border color each time it rendered, forcing React to change the DOM. By using a "
      [:a {:href "https://github.com/circleci/frontend/blob/7cf4a54480bc5aa772f465afced98d8f3d84dc8a/src-cljs/frontend/utils.clj#L51"}
       "custom " [:code "html"] " macro"]
      ", I was able to toggle it on and off globally."]
     [:p "In the example below, we can quickly see that typing a new character into the input box "
      "is triggering re-renders in unrelated components."]]

    [:figure [:video {:controls "", :src "/assets/video/render-colors.mp4"}]]

    [:article
     [:p "This is a nice step forward. Like paint rectangles, it gives us a visual indication of possible "
      "performance bottlenecks. But we can do so much better. Paint rectangles can show us the regions that "
      "are being re-rendered, but that’s not how we think of components when we write and use them. Like any "
      "function, we want to refer to them by their name. The paint rectangles also don’t tell us how long it "
      "takes to render each region, so we don’t know which component to prioritize."]]

    [:article
     [:h3 "Instrumenting components"]
     [:p "A better solution would keep track of the time it took to render each component and refer to the component "
      "by its DisplayName. We can do that if we start a clock in the componentWillUpdate lifecycle event and stop it "
      "in the componentDidUpdate event. Om makes this very easy by letting us instrument our components and provide "
      "our own versions of the react lifecycle events. With "
      [:a {:href "https://github.com/circleci/frontend/commit/2f58976e57000c448a22440e69573c4f0b7c581b"} "~100 lines of code"]
      " and a few hours of free time, I was able to add meaningful performance instrumentation to our application."]]

     [:figure [:video {:controls "", :src "/assets/video/real-instrumentation.mp4"}] " "]

     [:article
      [:p "The console-style instrumentation panel drops down when we hit Ctrl+j and we can see it update in real-time "
       "as components are rendered."]
      [:p "Now it’s easy to identify the bottlenecks and measure the effects of our optimizations on render times."]])})
