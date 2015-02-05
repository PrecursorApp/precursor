(ns pc.views.blog.product-hunt-wake-up-call
  (:require [ring.middleware.anti-forgery :refer (wrap-anti-forgery)]
            [pc.views.common :refer (cdn-path)]
            [pc.views.blog.common :as common]))

(defn product-hunt-wake-up-call []
  {:title "Product Hunt was a wake up call."
   :blurb "What we found most interesting about the Product Hunt traffic was not its volume, but its quality of feedback..."
   :author "Danny"
   :body
   (list
    [:article
     [:p "On November 15 around 8am, I woke up to nearly 100 notifications on my phone.
         In my mostly-asleep state I assumed all the noises were just my alarm.
         When I finally got up and slid my phone open, I was shocked by what I found."]
     [:p "The previous night, my friend Daniel and I shared our weekend project on Hacker News.
         It was new and we were interested in feedback on what to add next.
         Unfortunately the post didn't receive much attention and we went to bed disheartened, without any real feedback."]
     [:p "Julie, a designer from Paris, found our project later that night and submitted it to Product Hunt.
         It quickly rose to #1 and attracted a slew of European traffic.
         Daniel set up Slack alerts for us when someone signed up or tried chatting with us in the app.
         The notifications I woke up to were those alerts.
         People were trying the app but we weren't around to interact with them!
         As soon as I realized this I instantly jumped out of bed, grabbed my computer, and started chatting with all of our new users.
         I called to wake Daniel up as well."]]

    [:figure
     [:a.img {:href "http://www.producthunt.com/posts/precursor" :data-caption-white "Thanks, Julie!"}
      [:img {:src (cdn-path "/blog/product-hunt-wake-up-call/julie-post.png")}]]]

    [:article
     [:h3 "The product is called Precursor."]
     [:p "Precursor is no-nonsense prototyping tool designed around team workflows.
         To start prototyping, go to "
         [:a {:href "/"} "prcrsr.com"]
         ", select a tool with right-click, and then draw.
         That's it--there's no software to install or sign up required.
         If you want to show your prototypes to a teammate, just send them a link; you'll instantly be able to collaborate in real-time.
         There's no jumping through hoops just to share your idea."]
     [:p "Daniel and I have both worked with a lot of remote developers and we find it difficult to share rough ideas quickly with teammates.
         We researched many prototyping tools but we couldn't find any that met our expectations in terms of simplicity and efficient collaboration.
         Everything was overly complex and made impromptu teamwork a chore.
         Rather than settling, we decided to scratch our own itch."]
     [:p "Our goal was to express our ideas fast while simultaneously making them dead simple to share.
         It's frustrating to lose a good idea over something that's easily preventable.
         We never want to lose another idea because we held it for too long or misplaced a sketch of it."]]

    [:figure
     [:a.img {:href "/" :data-caption-black "It's faster than even we expected."}
      [:img {:src (cdn-path "/email/collaboration-demo.gif")}]]]

    [:article
     [:h3 "Product Hunt loved Precursor, and we loved the feedback."]
     [:p "By mid-day Precursor was being shared on several other sites, but Product Hunt was still responsible for the majority of our traffic.
         What we found most interesting about the Product Hunt traffic was not its volume, but its quality of feedback.
         Over and over again, the Product Hunt community offered us insightful and actionable feedback."]
     [:p "Users understood that Precursor just got started, and that it was a minimum viable product to a more well-defined prototyping solution.
         Rather than write us off because of missing features, they told us which features they'd like to see next.
         Many even shared their use-case; telling us how they'll use it, how it helps now, and how it could help more in the future."]
     [:p "The Product Hunt feedback was too valuable to waste so we began handling support in real-time.
         If a question was asked in-app, we arrived within seconds to respond and collect feedback.
         We even started responding to feature requests by building the requested feature, rather than just a promise to do it later.
         Once users told us exactly what they wanted it was easy to prioritize and build things quickly."]
     (common/tweet "andymerskin" "534549512354672641")]

    [:article
     [:h3 "We'll keep growing."]
     [:p "Precursor started as a weekend project but Product Hunt showed us that there's interest in a solution like this.
         Even now, many users are still requesting new features that make collaborating with their team even easier.
         We're excited to continue building and refining Precusor until it becomes an essential tool."]
     [:p "Our most requested feature is a way to share ideas privately, so we're making private docs our top priority.
         In fact, we're nearly done with an early build of private docs.
         If your team is interested in early access, say "
         [:a {:href "mailto:hi@prcrsr.com?Subject=I%20am%20interested%20in%20private%20docs"} "hi@prcrsr.com"]]]

    [:article
     [:h3 "All of this happened in one weekend."]
     [:ul
      [:li "69,306 shapes created"]
      [:li "17,876 pageviews"]
      [:li "12,437 unique docs"]
      [:li "9,547 total users"]
      [:li "2,368 docs with multiple views"]
      [:li "1,025 docs with multiple collaborators"]
      [:li "464 collaborators joined " [:a {:href "/document/17592186400920"} "Julie's doc"]]
      [:li "270 Redditors made " [:a {:href "/document/17592187872308"} "this masterpiece"]]]]

    [:figure
     [:a {:data-caption-white "This is what our traffic looked like before and after the Product Hunt post.
                              That dip on the 16th was actually our server crashing :("}
      [:img {:src (cdn-path "/blog/product-hunt-wake-up-call/traffic-spike.png")}]]
     [:a {:data-caption-white "Here's an hourly overview of the same data.
                              We think the server crashed under the load but we're not sure since we were asleep.
                              We've upgraded our hardware since then."}
      [:img {:src (cdn-path "/blog/product-hunt-wake-up-call/server-crash.png")}]]
     (common/tweet "benarent" "534592531917312000")
     (common/tweet "fotosdelviaje" "534070940120780801")
     (common/tweet "magmip" "534074309191688192" :no-parent true)
     (common/tweet "TimDaub" "534351950419345408")])})
