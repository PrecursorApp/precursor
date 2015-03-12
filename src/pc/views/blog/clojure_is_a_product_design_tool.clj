(ns pc.views.blog.clojure-is-a-product-design-tool
  (:require [pc.views.blog.common :as common]
            [pc.views.common :refer (cdn-path)]))

(def example-haml
"%p
  Sentence with period after
  = succeed \".\" do
    %a{href: \"#\"} a link")

(def example-cljs
"[:p \"Sentence with period after \" [:a {:href \"#\"} \"a link\"] \".\"]")

(def example-path
"...
:stroke-menu-top \"M5,25h90\"
:stroke-menu-mid \"M5,50h90\"
:stroke-menu-btm \"M5,75h90\"
:stroke-lock-top \"M75,45V30C75,16.2,63.8,5,50,5S25,16.2,25,30v15\"
:filled-lock-btm \"M87.5,95h-75V45h75V95z\"
...")

(def example-icon
"...
:menu {:paths [:stroke-menu-top
               :stroke-menu-mid
               :stroke-menu-btm]}
:lock {:paths [:stroke-lock-top
               :filled-lock-btm]}
...")

(def example-less
"[class|=\"stroke\"] {
  fill: none;
  stroke: @color;
}
[class|=\"filled\"] {
  fill: @color;
  stroke: none;
}")

(def example-call
"[:div (common/icon :menu)]")

(defn clojure-is-a-product-design-tool []
  {:title "Clojure is a product design tool."
   :blurb "Clojure makes me more efficient and extends my reach as a designer. And it protects me from complex design tools..."
   :author "Danny"
   :image (cdn-path "/blog/clojurescript-is-a-design-tool/illustrator-onoff.gif")
   :body
   (list
    [:article
     [:p "As a product designer I’m obsessed with solving problems, and lately I’ve been designing with a niche programming language."]
     [:p [:a {:href "http://clojure.org/"} "Clojure"]
         " carries a stark notoriety among developers, despite being a tremendous feat of design. "
         [:a {:href "http://blog.venanti.us/why-clojure/"} "David Jarvis"]
         ", a former teammate of mine, encountered that reputation during an interview last year."]]

    [:article
     [:blockquote
      [:p [:strong [:small "[David] "]]
          "... so long as it's not Lisp I'm sure we'll be fine!"]
      [:p [:strong [:small "[Them] "]]
          "... it's Clojure."]
      [:p [:strong [:small "[David] "]]
          "(not knowing what Clojure is) *strange gurgling sound*"]]]

    [:article
     [:p "Later that year I experienced Clojure's reputation for myself when I was invited to give a talk about "
         [:a {:href "https://github.com/clojure/clojurescript"} "ClojureScript"]
         "—a variant of Clojure that compiles to JavaScript."]
     [:p "After the talk, one developer told me that he had never met another designer that used Clojure.
         At first I was surprised, and then concerned."]
     [:p "\"Maybe there's a reason no other designers do this\", I thought.
         Then I remembered how I answered the first question I was asked that night—whether or not I enjoy writing ClojureScript.
         My answer was \"No.\""]]

    [:article
     [:h3 "I didn't get it initially."]
     [:p "At that time I was still working at CircleCI, and my answer reflected my experiences with a recent rewrite of our "
         [:a {:href "https://github.com/circleci/frontend"} "front end"]
         " web app to "
         [:a {:href "https://github.com/omcljs/om"} "Om"]
         "—a "
         [:a {:href "http://facebook.github.io/react/"} "React"]
         " wrapper for building interfaces."
         "The ordeal taught me discipline but it also made my job harder."]
     [:p "The Clojure syntax is completely chaotic, with parentheses everywhere—at least that's what I thought last year."]
     [:p "Since then I've since left CircleCI to work full-time on "
         [:a {:href "https://precursorapp.com/home"} "Precursor"]
         ", a collaborative prototyping tool that also has a ClojureScript front end."]
     [:p "As opposed to last year, not only would I say I enjoy writing ClojureScript now but—along with Om—it has ruined traditional HTML and JavaScript for me.
         I have a newfound sense of freedom, and that's why I started learning code in the first place."]]

    [:article
     [:h3 "Now it all makes sense."]
     [:p "I wasn't a designer for very long before I realized that I wanted to write my own code.
         High-fidelity mockups make terrible deliverables when it comes to product design.
         They waste huge amounts of time during development and never even see the light of day."]
     [:p "Learning HTML and CSS was the first step in taking ownership over my work.
         They kept me involved in most of development but I felt like a co-pilot, rather than the captain."]
     [:p "Preprocessors like Less, Sass, and Haml made things a little better.
         They taught me about fundamental programming concepts such as variables, functions, and loops."]
     [:p "It was JavaScript frameworks that led me to my goal.
         Learning to use ClojureScript, Om, and React finally made me feel like I was taking ownership of my design work from beginning to end."]]

    [:article
     [:h3 "ClojureScript saves me from other development tools."]
     [:p "Before ClojureScript, I mostly used Haml for templating.
         It helped me avoid some defects in HTML like repetitive tagging."]
     [:p "However, Haml is more like a compromise than a solution.
         Its initial beauty fades with the first link-punctuation ritual."]
     [:code.block
      [:span example-haml]]
     [:p "ClojureScript lets me avoid repetition and remains utterly simple while doing so.
         There's no song and dance; just solutions."]
     [:code.block
      [:span example-cljs]]
     [:p "My favorite part about ClojureScript, is that it shares its syntax with Clojure itself.
         I improve my understanding of Clojure as a side effect of my normal workflow.
         I can’t think of a better way to learn.
         That's great design, by any standard."]]

    [:article
     [:h3 "And it protects me from complex design workflows."]
     [:p "The icon system I made for Precursor is a good example of the inherent elegance within ClojureScript."]
     [:p "Using Illustrator, I constrain every icon to a single area.
         This helps me prevent inconsistent proportions."]]

    [:figure
     [:a.img {:data-caption-black "I pile icons on top of each other, and then I toggle their visibility to compare."}
      [:img {:src (cdn-path "/blog/clojurescript-is-a-design-tool/illustrator-onoff.gif")}]]]

    [:article
     [:p "I relate this process to ClojureScript with a map that defines all of my icon paths."]]

    [:article
     [:code.block
      [:span example-path]]]

    [:article
     [:p "Then I specify which path goes where."]]

    [:article
     [:code.block
      [:span example-icon]]]

    [:article
     [:p "This type of structure makes styling a breeze."]]

    [:article
     [:code.block
      [:span example-less]]]

    [:article
     [:p "To call this function I just specify the icon I want."]]

    [:article
     [:code.block
      [:span example-call]]]

    [:article
     [:p "The paths associated with that icon get piled up and styled together.
         This lets me go from ideating to shipping in under 30 minutes."]]

    [:article
     [:h3 "Every product designer should know about Clojure."]
     [:p "I challenged myself to work outside my expertise, because I knew designing the same way everyday would dilute my passion."]
     [:p "My optimizations are individually subtle but they save me hours every week.
         I spend that time on more important things, like polish.
         And therein lies the point; Clojure makes me more efficient and extends my reach as a designer."]
     [:p "Don’t let fear prevent growth.
         You don’t have to learn Clojure—even though it’d be nice knowing I'm not alone—you just need to learn something.
         There's something you can learn right now that will help you a build better products.
         Find it and make something."]]

    [:article.blogpost-footnote
     [:p
      "Thanks "
      [:a {:href "https://twitter.com/sgrove"       :title "@sgrove"}       "Sean Grove"] ", "
      [:a {:href "https://twitter.com/BrandonBloom" :title "@BrandonBloom"} "Brandon Bloom"] ", "
      [:a {:href "https://twitter.com/Venantius"    :title "@Venantius"}    "David Jarvis"] ", & "
      [:a {:href "https://twitter.com/futurepaul"   :title "@futurepaul"}   "Paul Miller"]
      " for reading drafts of this."]]
    )})
