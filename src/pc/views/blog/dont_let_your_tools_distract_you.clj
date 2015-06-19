(ns pc.views.blog.dont-let-your-tools-distract-you
  (:require [pc.views.common :refer (cdn-path)]))

(defn dont-let-your-tools-distract-you []
  {:title "Don't let your tools distract you. "
   :blurb "Knowing where to draw the line is difficult. It's important to remember that prototyping tools are useful because they're simple. "
   :author "Danny"
   :body
   (list
    [:article
     [:p "One of the most requested features we receive for Precursor is for stencils. "
         "Stencils are pretty common in wireframing, and most prototyping apps offer more stencils than you know what to do with. "
         "But we’re not going to do that, and here’s why. "]
     [:p "Stencils don't deliver on the promise of more productivity. "]
     [:p "If you've never used one, stencils are pre-made components that developers put together as shortcuts for sketching basic UI elements. "
         "Sick of drawing the same dropdown? "
         "Use a stencil. "]
     [:p "The concept of helping users work fast and avoid repeating themselves is great; but the traditional implementation is flawed. "
         "While stencils do make some tasks faster, they usually do so at the cost of a user's attention. "]
     [:p "Last week we released a new feature called Clipboard History (or “clips” for short). "
         "This feature is unique for a prototyping tool, so we wanted to test it out with users before promoting it. "]
     [:p "After speaking with the users who have been testing it, we’re excited that people are finding it to be a helpful addition to their workflow. "
         "So we thought it'd be best to introduce clips today with a little backstory, and then I’ll show you how it works. "]]

    [:article
     [:h3 "Normal stencils trade one problem for another. "]
     [:p "When we first discussed the option of stencils, I was hesitant to approach it the way most prototyping tools do. "
         "They always start with the best intentions—developers want to give users a way to work faster and avoid repeating themselves—but they end up creating an entirely new problem. "]
     [:p "Knowing where to draw the line is difficult. "
         "It's important to remember that prototyping tools are useful because they're simple. "
         "Other alternatives like Photoshop and Illustrator require too much cognitive load for such a simplistic workflow. "
         "Prototyping is essentially brainstorming, and minimizing distractions while brainstorming is critical. "
         "Each additional task in front of a user has the potential to destroy an idea before it solidifies. "]
     [:p "If I'm looking for a single stencil and I'm presented with hundreds of interesting choices, I'll lose focus every time. "
         "I stop considering what exact tool I need to solve the problem before me, and I start imagining the possibilities of using one of these other cool things I just discovered. "]
     [:p "Having been distracted by this process many times in the past, I became committed to finding a new approach. "
         "This is where clips come in. "]]

    [:article
     [:h3 "We’re adding history to your clipboard. "]
     [:p "Soon we’ll combine this history of your copied shapes shapes with a new copy and paste interaction to help users sketch fast, while remaining focused on the ideas in front of them. "]
     [:p "Right now we have things setup to operate mostly from a list in the menu, but the next step is to create something that enables users to work faster and avoid unnecessary distractions at the same time. "]
     [:p "We're going to give users the ability to quickly bind shapes to number keys and reproduce them at anytime using a shortcut. "]]

    [:article
     [:h3 "Shortcuts aren’t intuitive. "]
     [:p "We're riding the coattails of a well-known shortcut: copy and paste. "
         "When shapes are copied and pasted, we're not just going to drop them into the center of the viewport anymore. "
         "Instead we're going to start being a bit smarter about how we let users place pasted shapes. "]
     [:p "When pasting shapes, they'll appear over the canvas, following the cursor position. "
         "By doing this, users will be able to position pasted items before committing them to the canvas with a simple click. "]
     [:p "This interim period, between pasting and dropping, creates an opportunity for alternate endings to the whole interaction. "
         "In addition to the normal paste behavior, you'll also be able to scroll through your saved clips and paste one of them instead. "]
     [:p "Everyone is already familiar with how copy and paste works. "
         "So by piggybacking on well-known behavior, we’re going to be able to deliver an advanced user technique in a way that's intuitive. "
         "The most important benefit to all of this is that users will have an easy way to save time that's better spent on ideating and iterating with teammates. "]]

    [:article
     [:h3 "Simplicity is the goal. "]
     [:p "By far, the most common piece of feedback we get at Precursor is, \"Thanks for making something so simple.\" "
         "I love comments like this, because they reassure me that we're on the right track. "]
     [:p "As competition between prototyping tools continues to evolve, I think we're going to see more tools offer more solutions, using more UI. "
         "The irony in all of this extra effort is that it takes users further from the original goal of prototyping, which is to lower cognitive load. "]
     [:p "At Precursor, every feature we consider is subjected to scrutiny against this original goal. "
         "I believe this new approach to stenciling is great example of our dedication to simplicity. "
         "It will help users be more productive and solve problems that really matter. "]]

    [:article
     [:h3 "Let's try it real quick. "]
     [:p "Clips are simple, but they’re also a new approach to wireframing. "
         "Once you try it I think you'll agree it's useful, and once you see our future plans, I think you'll get excited. "]]

    [:article
     [:h4 "1) Copy shapes. "]
     [:p "Use the select tool (V) to select shapes, then use Cmd/Ctrl+C to copy them. "
         "This also adds them to your Clipboard History in the menu. "]]

    [:figure
     [:a.img {:data-caption-black "I pile icons on top of each other, and then I toggle their visibility to compare. "}
      [:img {:src (cdn-path "/blog/clojurescript-is-a-design-tool/illustrator-onoff.gif")}]]]

    [:article
     [:h4 "2) Paste shapes. "]
     [:p "Now open your Clipboard History (C) and click the thumbnail of the shape you just copied to paste it into your doc. "]]

    [:figure
     [:a.img {:data-caption-black "I pile icons on top of each other, and then I toggle their visibility to compare. "}
      [:img {:src (cdn-path "/blog/clojurescript-is-a-design-tool/illustrator-onoff.gif")}]]]

    [:article
     [:h4 "3) Favorite shapes. "]
     [:p "Click the star icon for the shapes you want to remember. "
         "Your favorite shapes will pin to the top of this list. "]]

    [:figure
     [:a.img {:data-caption-black "I pile icons on top of each other, and then I toggle their visibility to compare. "}
      [:img {:src (cdn-path "/blog/clojurescript-is-a-design-tool/illustrator-onoff.gif")}]]]

    [:article.blogpost-author
     [:p
      "Written by "
      [:a {:href "https://twitter.com/dannykingme"} "Danny King"]
      ". "]]

    )})
