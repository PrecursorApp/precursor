(ns pc.views.blog.blue-ocean-made-of-ink)

(defn blue-ocean-made-of-ink []
  {:title "Our blue ocean is made of ink."
   :blurb "For every single designer or developer using an app to prototype their product, there's at least a thousand using pen and paper..."
   :author "Danny"
   :body
   (list
    [:article
     [:p "Following our unexpected "
         [:a {:href "/blog/product-hunt-wake-up-call"} "Product Hunt launch"]
         ", we’ve been receiving a lot of feedback.
         When it’s positive I’m inspired, and when it’s critical I’m motivated.
         We’ve even received encouragement from founders of more popular prototyping tools.
         This type of praise often brings up the question, \"Aren't they competition?\""]]

    [:article
     [:blockquote "Our true competition is not the small trickle of non-Tesla electric cars being produced, but rather the enormous flood of gasoline cars pouring out of the world’s factories every day."]]

    [:article
     [:p "Elon Musk wrote that on "
         [:a {:href "http://www.teslamotors.com/blog/all-our-patent-are-belong-you"} "Telsa's blog"]
         " last year.
         We're lucky enough to stand on the shores of a similar ocean.
         For every single designer or developer using an app to prototype their product, there's at least a thousand using pen and paper."]]

    [:article
     [:h3 "How do we compete with paper?"]
     [:p "I don’t handwrite letters, I won't pay bills with checks, and I never unfold maps to get around.
         So why is pen and paper still so ingrained in the prototyping process?
         In my experience prototyping designs on paper is often based on a desire for more discipline—and this is where Precursor will compete.
         The inherent discipline with pen and paper makes it simple, efficient, and accessible."]]

    [:article
     [:h3 "Simplicity—good start."]
     [:p "No tool is simpler than pen and paper.
         It requires almost no conscious effort.
         Competing here means creating a minimum level of cognitive load.
         To avoid gratuity we interface only when necessary and omit common customization options.
         These constraints help us focus on productive interactions.
         E.g., when all shapes are white we can colorize collaborators and create instant recognition."]]

    [:article
     [:h3 "Efficiency—almost there."]
     [:p "This could be pen and paper's greatest strength.
         I’ve always returned to paper after finding that other prototyping tools are too slow.
         By contrast, I've been using Precursor in my normal workflow for months and I can’t remember when I last reached for a pen.
         I save time that’s better spent polishing designs in production.
         I’m close to eliminating pen and paper from my own process entirely."]]

    [:article
     [:h3 "Accessibility—done."]
     [:p "Collaboration is the Precursor flagship.
         Handing someone a notebook is easy, but no easier than handing them a phone or a tablet.
         Precursor can even connect collaborators all over the world.
         Within seconds an entire product team can be communicating and designing the same wireframe in real time."]]

    [:article
     [:h3 "Let's be more productive."]
     [:p "Paper is inevitably going to be lost, damaged, or forgotten.
         I justified this to myself by assuming my prototypes were disposable, but I've never actually found this to be true.
         Every time I look back through my early prototypes on Precursor, I learn something new about my process.
         Consider the implications of a system like that for a moment.
         Imagine being able to recall every drawing, sketch, or doodle you ever scratched on a piece of paper.
         How amazing would that be?"]]
    )})
