(ns pc.views.blog.diagramming-with-precursor
  (:require [pc.views.blog.common :as common]
            [pc.views.common :refer (cdn-path external-cdn-path)]))

(defn diagramming-with-precursor []
  {:title "Diagramming with Precursor"
   :blurb "Learn how to build flow diagrams as we sketch out Precursor's infrastructure."
   :author "Daniel"
   :image (external-cdn-path "/blog/diagramming-with-precursor/full-infrastructure.png")
   :body
   (list
    [:article
     [:p "Precursor works great for building flow diagrams. It has the sketching tools you need to clearly express your ideas, but keeps things simple so that you don't get distracted from your goal."]

     [:h3 "Why flow diagrams?"]
     [:p "Flow diagrams can help you design and understand complex systems. They provide a high-level overview of a system that helps you anchor your thinking and visualize relationships between the system's components."]
     [:p "A common mistake in sketching flow diagrams is to add too much detail. It may seem important to build a comprehensive diagram, but your audience (which may be you in a couple of months) will get overwhelmed. It's better to err on the side of being too abstract. If you need to, you can always add more detail later. If your diagram starts to get too complex, consider extracting the complex parts into separate diagrams."]

     [:h3 "Let's build something"]
     [:p "For a case-study in sketching flow diagrams, we're going to map out Precursor's infrastructure."]
     [:p "Precursor's infrastructure is made up of 2 main types of components: 1. ones that store data and 2. ones that process data. We'll represent components that store data with the classic spinning disks icon. I'm a pretty poor artist, but I searched Google for \"database icon\" and they're just a stack of circles. We'll spend 30 seconds and illustrate one ourselves. Then when we need it to represent a component, we can just copy/paste it into place."]

     (common/demo (external-cdn-path "/blog/diagramming-with-precursor/db-placeholder.png")
                  (external-cdn-path "/blog/diagramming-with-precursor/db.gif"))

     [:article
      [:p "For components that process data, we'll illustrate a blade server. Again, I searched Google for server icon and the basic shape is just a rectangle in front and a couple of narrowing lines traveling backwards. I'll take another 30 seconds to illustrate one of these."]]

     (common/demo (external-cdn-path "/blog/diagramming-with-precursor/blade-placeholder.png")
                  (external-cdn-path "/blog/diagramming-with-precursor/blade.gif"))

     [:article
      [:p "If you’re thinking you can't sketch, banish that thought. Anyone can sketch well enough to get an idea across. You don't need to waste time searching for and importing the same icon that AWS uses. We're not making marketing materials, we're making diagrams that you use to communicate your ideas to others. Identify the basic shape of the icon, and sketch it as faithfully as you can in 30 seconds. If you're really that bad, you can make a legend to translate your scribbles into English."]
      [:p "Now that we have our main components, we can start putting them together. I like to start mapping the system from the perspective of a user visiting the site. That means that I'll need to illustrate a couple of laptops to represent the users."]]

     (common/demo (external-cdn-path "/blog/diagramming-with-precursor/clients-placeholder.png")
                  (external-cdn-path "/blog/diagramming-with-precursor/clients.gif"))

     [:article
      [:p "When the user makes the request, it goes through the load balancer to a web server. The web server delivers a web page with links to javascript and css. Those assets live on our CDN and the client fetches them directly from there. We'll use arrows to indicate the direction of data flow. To create an arrow, hold \"A\" then click on the borders of shapes to connect them."]]

     [:figure
      [:a.img {:data-caption-black "Hold \"A\" and click the edges of shapes to create arrows."}
       [:img {:src (external-cdn-path "/blog/diagramming-with-precursor/web-part.png")}]]]

     [:article
      [:p "How did those assets get in the CDN? Somebody pushed some code to GitHub, CircleCI fetched the code, built the assets, ran some tests, then deployed the assets to s3. On the first request, the CDN fetched the assets from s3."]]

     [:figure
      [:a.img {:data-caption-black "Hold \"A\" and click the edges of shapes to create arrows."}
       [:img {:src (external-cdn-path "/blog/diagramming-with-precursor/web-and-build-server-parts.png")}]]]

     [:article
      [:p "Now the user sketches some shapes. Those get sent to the web server which forwards them to the Datomic transactor. The transactor stores them in our Postgres database. The next time someone loads the page, the Datomic peer in the web server will fetch the shapes from Postgres."]]

     [:figure
      [:a.img {:data-caption-black "Hold \"A\" and click the edges of shapes to create arrows."}
       [:img {:src (external-cdn-path "/blog/diagramming-with-precursor/full-infrastructure.png")}]]]

     [:article
      [:p "Now we have a good overview of our infrastructure. When a new engineer joins the team, this is one of the first things we'll show them. It's even embedded in our GitHub README. You can see an example of that "
       [:a {:href "https://github.com/PrecursorApp/image-refresh-demo"}
        "in our demo repo"]
       "."]
      [:p "The final diagram is "
       [:a {:href "https://precursor.precursorapp.com/document/17592197950857"}
        "available as a read-only doc"]
       ". If you want to use it as a starting point, just select the parts you want, hit Cmd+c or Ctrl+c to copy, and Cmd+v or Ctrl+v to paste them into a new document."]]

     [:h3 "Tools review"]

     [:article
      [:p "Connect shapes with an arrow by holding \"A\" and clicking on the borders of shapes to connect them."]
      [:p "Copy and paste shapes by selecting them and hitting your system's copy/paste shortcut (Ctrl+c/Ctrl+v on Windows, Cmd+c/Cmd+v on mac)."]
      [:p "Hold \"Alt\" and scroll to zoom in. You can do more precise placement when zoomed."]
      [:p "Hit \"+\" or \"-\" to resize selected text."]
      [:p "Export docs as svg and "
       [:a {:href "https://github.com/PrecursorApp/image-refresh-demo"}
        "embed them in a GitHub README"]
       "."]]

     ])})
