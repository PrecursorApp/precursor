(ns pc.views.blog.product-hunt-wake-up-call
  (:require [ring.middleware.anti-forgery :refer (wrap-anti-forgery)]))

(defn product-hunt-wake-up-call []
  {:title "Product Hunt was a wake up call, literally."
   :blurb "What we found most interesting about the Product Hunt traffic was not its volume, but its quality of feedback..."
   :author "Danny"
   :body
   (list
    [:article
     [:p "On November 15 around 8am, I woke up to nearly 100 notifications on my phone.
         In my mostly-asleep state I assumed all the noises were just my alarm.
         When I finally got up and slid my phone open, I was shocked by what I found."]
     [:p "The previous night, my friend Daniel and I shared our weekend project on with Hacker News.
         Knowing that the project was still very young, we were interested in suggestions of what features to add next.
         We received some feedback but nothing substantial."]
     [:p "Later that night, a designer from Paris named Julie found our project and submitted it to Product Hunt.
         It quickly rose to #1 and attracted a slew of European traffic.
         Luckily, Daniel had previously set up alerts for us when someone signed up or tried chatting with us in the app.
         The notifications I woke up to were those alerts.
         People were trying the app but we weren't around to interact with them.
         As soon as I realized this I instantly jumped out of bed, grabbed my computer, and started chatting with all of our new users.
         I called to wake Daniel up as well."]]

    [:figure
     [:a.img {:href "http://www.producthunt.com/posts/precursor" :data-caption-white "Thanks, Julie!"}
      [:img {:src "/blog/product-hunt-wake-up-call/julie-post.png"}]]]

    [:article
     [:h3 "The product is called Precursor."]
     [:p "Precursor is no-nonsense prototyping tool designed around team workflows.
         To start prototyping, simply visit the root url, right-click to select a tool, and then draw.
         That's it--there's no software to install or sign up required.
         If you want to show your prototypes to a teammate, just send them a link; you'll instantly be able to collaborate in real-time.
         There's no jumping through hoops just to share your idea."]]

    [:figure
     [:a.img {:href "/" :data-caption-black "It's faster than even we expected."}
      [:img {:src "/email/collaboration-demo.gif"}]]]

    [:article
     [:p "Daniel and I both work with a lot of remote developers and we find it difficult to share quick, rough sketches with teammates.
         We researched many prototyping tools but we couldn't find one that met our expectations in terms of simple, efficient collaboration.
         Everything was overly complex and made impromptu teamwork a chore.
         Rather than settling, we decided to scratch our own itch."]
     [:p "Our initial goal was to simply help ourselves share ideas as efficiently and effectively as possible, and then make it insanely easy to share those ideas.
         We know the pain of losing a good idea; the worst part for me is knowing that it didn't have to happen.
         I wouldn't have lost that idea if had I sketched it out sooner or if I drew it on something that couldn't get misplaced."]]

    [:article
     [:h3 "Product Hunt loved Precursor, and we loved the feedback."]
     [:p "By mid-day Precursor had been shared on several other sites, but Product Hunt was still responsible for the majority of our traffic.
         What we found most interesting about the Product Hunt traffic was not its volume, but its quality of feedback.
         Over and over again, the Product Hunt community offered us insightful and actionable feedback.
         Most of the time it even considered many constraints that the project actually faced."]
     [:p "Users understood that Precursor just got started, and that it was a minimum viable product to a more well-defined prototyping solution.
         I was astounded every time someone acknowledged this explicitly.
         Rather than write us off because of missing features, they told us which features they'd like to see next.
         Many even shared their use-case; telling us how they'll use it, how it helps now, and how it could help more in the future.
         I've never witnessed such appropriate feedback, and it came from complete strangers."]
     [:p "The Product Hunt feedback was too valuable to waste so we began handling support in real-time.
         If a question was asked in-app, we arrived within seconds to respond and collect feedback.
         We even started responding to feature requests by building the requested feature, rather than just a promise to do it later.
         Once users told us exactly what they wanted it was easy to prioritize and build things the same day."]
     [:p "Precursor started as just a weekend project for us, but Product Hunt has shown us that there's significant interest in the problem we're trying to solve.
         I'm happy to say that we're no longer just adding features on the weekends; we'll be spending a lot more time on it in the coming weeks and plan to work on it full-time."]
     [:p "The next big features we have planned are private docs and team features.
         Many people have asked for the ability to share ideas with their team privately, so we've made this our top priority.
         Hopefully the Product Hunt community will have us back again at some point to share a new offering."]]

    [:article
     [:h3 "tl;dr Here's how we did."]
     [:p "Precursor has only been live in its current form for a few months.
         Luckily, we set up analytics just before we were featured on Product Hunt.
         Here's a few stats that we found interesting:"]
     [:ul
      [:li "69,306 layers created"]
      [:li "17,876 pageviews"]
      [:li "12,437 unique docs"]
      [:li "9,547 total users"]
      [:li "2,368 docs with multiple views"]
      [:li "1,025 docs with multiple collaborators"]
      [:li "464 collaborators joined " [:a {:href "/document/17592186400920"} "Julie's doc"]]
      [:li "270 Redditors made " [:a {:href "/document/17592187872308"} "this masterpiece"]]]]

    [:figure
     [:img {:src "/blog/product-hunt-wake-up-call/traffic-spike.png"}]]

    [:article
     [:p "After that weekend we broke 300 sign ups.
         To put that number into perspective, before Product Hunt we had less than 10 sign ups.
         We didn't have much of a sign up process either.
         At that point almost everything in the app could be done without signing up."]
     [:p "The morning after being on Product Hunt, our server went down from 2am to 7am.
         The load was likely enough to take it down, but we don't know for sure since we were asleep."]]

    [:figure
     [:img {:src "/blog/product-hunt-wake-up-call/server-crash.png"}]]

    [:article
     [:p "All in all, it was a fantastic experience.
         We've learned a lot about our product in the last few weeks, and now we feel like we have a well-defined direction for growth."]]
    )})
