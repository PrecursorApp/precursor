(ns pc.views.blog.blue-ocean-made-of-ink)

(defn blue-ocean-made-of-ink []
  {:title "Our blue ocean is made of ink."
   :blurb "For every single designer or developer using an app to prototype their product, there are hundreds more using paper..."
   :author "Danny"
   :body
   (list
    [:article
     [:p "Following our unexpected "
         [:a {:href "/blog/product-hunt-wake-up-call"} "Product Hunt launch"]
         ", we've received a lot of feedback from users.
         I've found the positive comments to be inspiring, and critical ones motivating.
         We've even been receiving encouragement from founders of more popular prototyping apps.
         This type of praise begs the question, \"Aren't they competition?\""]]

    [:article
     [:blockquote "Our true competition is not the small trickle of non-Tesla electric cars being produced, but rather the enormous flood of gasoline cars pouring out of the world's factories every day."]]

    [:article
     [:p [:a {:href "http://www.teslamotors.com/blog/all-our-patent-are-belong-you"} "Elon Musk"]
         " said that about Telsa last year.
         Prototyping stands on similar shores.
         For every single designer or developer using an app to prototype their product, there are hundreds more using paper."]]

    [:article
     [:h3 "Can we compete with paper?"]
     [:p "I don't handwrite letters, I won't pay bills with checks, and I never unfold maps to get around.
         So why is pen and paper still so utterly ingrained in the traditional design process?
         In my case, it's often tied to a desire for more discipline.
         Its constraints make pen and paper simple, efficient, and accessible.
         So that's where we'll start."]]

    [:article
     [:h3 "Simplicity&mdash;a good start."]
     [:p "Pen and paper demands no conscious effort.
         So competing here requires a minimum level of cognitive load.
         This should explain our lean interface and modest amount of customization options.
         Our constraints help us design productive behaviors.
         E.g., monotone shapes make emphasizing collaborator activity simple with color."]]

    [:article
     [:h3 "Efficiency&mdash;almost there."]
    [:p "I often retreat to pen and paper after finding other prototyping tools to be unwieldy.
        By contrast, Precursor's agility has kept me from reaching for a pen for months.
        Its precision helps me review old prototypes even after I've forgotten the context.
        I save time early in my design process so I can polish more in production."]]

    [:article
     [:h3 "Accessibility&mdash;done."]
     [:p "Collaboration is the Precursor flagship.
         Handing someone a notebook is easy, but no easier than handing them a phone or a tablet.
         Precursor takes the advantage by connecting collaborators around the world.
         Entire product teams can be communicating and designing concepts together in real time, within seconds."]]

    [:article
     [:h3 "We're more productive without pen and paper."]
     [:p "Paper inevitably gets damaged or forgotten.
         I tried justifying that by looking at my sketches as disposable, but now I'm starting to realize this isn't true.
         Whenever I scroll through past prototypes on Precursor, I learn something new about my process.
         Consider the implications of a system like that for a moment.
         Imagine recalling every drawing or doodle you ever scratched on a piece of paper.
         How amazing would that be?"]]
    )})
