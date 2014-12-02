(ns pc.views.blog.replace-pen-and-paper)

(defn replace-pen-and-paper []
  {:title "Let's replace pen and paper."
   :blurb "I've worked with many remote teammates and trying to share paper sketches with them is almost impossible..."
   :author "Danny"
   :body
   (list
    [:article
     [:p "Jason Fried wrote a post that explained why his team avoids Photoshop for UI design. "
         "The problem is that some tools add more cognitive load to a process than they offer in value. "
         "I admire this type of process refinement through reflection. "
         "I also think it's relevant to other parts of design, such as the ideation. "]
     [:p "Someone asked the following question in the comments section of that post.
         The question seems so simple, and yet it outlines a very complicated problem."]]

    [:article
     [:blockquote "How do you share paper sketches with the people outside of Chicago?"]]

    [:article
     [:h3 "Remote collaboration is hard with pen and paper."]
     [:p "I've worked with many remote teammates and trying to share paper sketches with them is almost impossible. "
         "I remember practicing the ol' email-a-photo technique myself. "
         "Interestingly, further down from the quote above, others admitted that they actually scan their drawings. Whoa."]
     [:p "This is enough of a problem that I bet we could make another list similar to the Photoshop list. "
         "We'll even reference another hallmark of the tradition design process; pen and paper. "
         "Only this time we'll focus on the ideation/brainstorming stage of design."]]

    [:article
     [:h3 "Here are a few reasons we want to replace pen and paper:"]
     [:ol
      [:li "You can't click pen and paper.
           Paper may not have this expectation, but wouldn't you rather identify problems with your user flow immediately rather than just before you ship?
           I want to be able to sketch my ideas fast and then go straight to clicking through different views."]
      [:li "Unless you're a talented illustrator, pen and paper sketches can be hard to understand.
           I'm terrible at drawing, so when I look at my old sketches I often feel like I have no context for what I'm seeing.
           It served me well when I drew it and the idea was fresh in my mind, but once I lose that thought my crude drawing just looks confusing."]
      [:li "Pen and paper goes missing constantly.
           If you asked me to show you all of the wireframes I've sketched out with paper in the last year, I might be able to show you ten out of the hundreds I've actually made.
           The rest are probably in a landfill somewhere; I honestly have no idea."]
      [:li "There's no inherent constraint with pen and paper.
           This might actually seem like a win for pen and paper, but I'm not a fan.
           If you're designing UI you'll face plenty of constraint eventually.
           Why wait to incorporate it into your process?
           I want relevant constraint all of the time.
           It tells me that I'm solving real problems rather than just making something pretty."]
      [:li "Pen and paper requires repetition.
           Imagine you feel inspired to create a simple wireframe for an address book.
           If you sketch it with pen and paper you'll have to draw the same element repeatedly for each contact in the list.
           I would rather just copy the first one and paste it a few times."]
      [:li "Pen and paper isn't collaborative.
           Even if someone is sitting right next to you, it's awkward to try and watch what they're doing.
           They either have to look over your shoulder or watch it happen upside down.
           You also can't draw on the same piece of paper, at the same time.
           Worse of all, you can't share pen and paper with someone in another part of the world."]
      [:li "Pen and paper is awkward.
           Again, I'm no artist, and I'm terrible with a pen.
           I've used them all my life, but I've certainly used my keyboard and mouse a lot more.
           My mouse is home.
           Creating something without it just feels foreign."]]]

    [:article
     [:h3 "Prototypes deserve more than just a pen and paper."]
     [:p "None of this is to say that I think pen and paper has no place anymore.
         I still use it for disposable ideas when I'm not close to my computer or tablet.
         But I want something better for my prototypes; something just as fast and simple to share as it is to use."]
     [:p "My friend, Daniel and I looked for something like this and didn't find it, so we built it ourselves.
         We only started a few weeks ago, but we already have a more creative and collaborative workflow as a result.
         Precursor allows us to be productive as a team in a way that pen and paper never will."]])})
