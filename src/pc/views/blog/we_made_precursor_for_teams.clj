(ns pc.views.blog.we-made-precursor-for-teams
  (:require [ring.middleware.anti-forgery :refer (wrap-anti-forgery)]
            [pc.http.urls :as urls]
            [pc.views.email :as email]
            [pc.product-hunt :as product-hunt]
            [pc.profile :as profile]
            [pc.views.common :refer (cdn-path external-cdn-path)]
            [pc.views.blog.common :as common]))

(defn we-made-precursor-for-teams []
  {:title "We made Precursor for teams."
   :blurb "We want users to know we're not going anywhere. Precursor for Teams will help us ensure your work is private and stays secure. "
   :author "Danny"
   :image (external-cdn-path "/blog/ideas-are-made-with-precursor/lobanovskiy-placeholder.gif")
   :body
   (list
    [:article
     [:p "I'm excited to announce that today we're launching Precursor for Teams on Product Hunt. "]]

    [:figure
     (product-hunt/product-hunt-component 19567)]

    [:article
     [:p "Product Hunt holds a special place in our hearts. "
         "We were featured there "
         [:a {:href "/blog/product-hunt-wake-up-call"} "several months ago"]
         ", back when Precursor was still just a part-time side project. "
         "And as a huge thank you to the community, we're offering 50% off Precursor for Teams to all Product Hunters, for the first 6 months. "]
     [:p "To take advantage of the discount, follow the link on Product Hunt, then click on the welcome message on the homepage. "]]

    [:article
     [:h3 "Our goal has always been a pretty simple one. "]
     [:p "We want to make sharing ideas with anyone effortless. "
         "To accomplish this we've been listening to your feedback, and using it to perfect the tools we've already made as well as adding exciting new ones. "]
     [:p "These "
         [:a {:href "/features/team"} "new features"]
         " will include the ability to make your docs private, so that your team's hard work is always secure. "
         "We've implemented voice chat, so you can have team meetings in the same place where you share your ideas. "
         "You'll also have your own team domain, so your team's ideas are all organized in one place. "]]

    [:article
     [:h3 "We're committing ourselves to helping teams communicate."]
     [:p "We want users to know we're not going anywhere. "
         "Precursor for Teams will help us ensure your work is private and stays secure. "]
     [:p "Your team should focus on what's important—their ideas. "
         "Let us handle everything else. "]]

    [:article
     [:h3 "And pricing is simple."]
     [:p "We think team collaboration should be simple, and so should "
         [:a {:href "/pricing"} "pricing"]
         ". "
         "The monthly cost for a team is $10 per active user, per month. "]
     [:p "If you prefer to work solo, that's okay too. "
         "You can still get a "
         [:a {:href "/trial"} "private domain"]
         " to use on Precursor and give people access to individual docs, without adding them to your team. "
         "It's perfect for sharing ideas with clients. "]]

    [:article
     [:h3 "Best of all, prototyping will still be free."]
     [:p "Our prototyping tools are staying right where they are—free for everyone. "]
     [:p "Accessibility is part of Precursor's foundation, and we still think sharing an idea should be as simple as sharing a url. "
         "That's why we're keeping the Precursor public app free and accessible anywhere, on any device. "]]

    [:article
     [:h3 "We're hungry for feedback."]
     [:p "Your feedback is invaluable to us, now more than ever. "
         "If you see something that we can do better, please let us know. "]
     [:p "Ping us in your doc's chat with \"@prcrsr\" or "
         [:a {:href (format "mailto:%s" (email/email-address "hi"))} "email us"]
         ". "
         "We'll be there to answer any questions, or if you just want a sketching buddy. "]]

    [:article
     [:h3 "All of this means better features now and in the future."]
     [:p "Now that Precursor has a real growth strategy, we can keep focusing on improving our core product. "]
     [:p "Our most exciting features are still ahead of us. "
         "But more importantly, we're going to continue making our current features better every day! "]
     [:p "We don't ignore good ideas. "
         "Below are some of the feature requests we've finished in just the last few weeks. "]
     [:br]]

    [:figure
     (common/tweet "PrecursorApp" "592941206104985600") ; export
     (common/tweet "PrecursorApp" "592801576714121216") ; voice
     ; (common/tweet "PrecursorApp" "586568570298957824") ; github embed
     ; (common/tweet "PrecursorApp" "585537704760324099") ; sms
     ; (common/tweet "PrecursorApp" "584082725901967360") ; space pan
     (common/tweet "PrecursorApp" "582964084850757632") ; replay
     ; (common/tweet "PrecursorApp" "582719313410326528") ; text sizing
     (common/tweet "PrecursorApp" "581525258764754944") ; read only
     (common/tweet "PrecursorApp" "580407786300801024") ; arrows


     ]

    )})
