(ns pc.views.blog.interactive-layers
  (:require [ring.middleware.anti-forgery :refer (wrap-anti-forgery)]))

(defn interactive-layers []
  {:title "Introduction to Interactive Layers"
   :blurb "Learn how to build complex interactive prototypes from Precursor's simple components."
   :author "Daniel"
   :body
   (list
    [:article
     [:p "In this tutorial, we'll show you how to use Precursor's simple interactive layers to prototype complex user flows."]
     [:p "We'll prototype a simple iPhone app with three different views."]
     [:form {:id "interactive-demo-form" :method "post" :action "/duplicate/interactive-demo"}
      [:input {:type "hidden" :name "__anti-forgery-token"
               :value ring.middleware.anti-forgery/*anti-forgery-token*}]
      [:p "You can "
       ;; falls back to email welcome gif if js doesn't execute for some reason
       [:a {:onclick "event.preventDefault(); document.getElementById('interactive-demo-form').submit()" :href "/email/welcome/interactive-demo.gif"}
        "follow along on Precursor"]
       "  with your own copy of the document we created for this tutorial."]]]

    [:figure [:img {:src "/email/interactive-demo.gif"}]]

    [:article
     [:h3 "Name Shapes"]
     [:p "We'll start by drawing out three static views of our iPhone game."]]

    [:figure [:img {:src "/blog/interactive-layers/static-view.png"}]]

    [:article
     [:p "We can right-click on the phone outline to open the properties menu and give each of our views a name. "
      "We'll name the first view \"Overview\", the second view \"Expanded Game\", and the third view \"Game main menu\". "]]

    [:figure [:img {:src "/blog/interactive-layers/name-layer.png"}]]


    [:article
     [:h3 "Define Targets"]
     [:p "Now that we've named our main views, we can right-click on a shape to select one of the views to target from the drop-down menu."]]

    [:figure [:img {:src "/blog/interactive-layers/select-target.png"}]]

    [:article
     [:p "After we've defined a target, clicking on the shape with the select tool will center its target on the canvas. "
      "We'll have the illusion of moving to a separate view, but we haven't left our prototyping world. "
      "This is the beauty of Precursor's interactive prototyping--it's built up of very simple pieces."]]

    [:article
     [:h3 "Extend the Illusion"]
     [:p "The transitions will feel more realistic if we can't see the next view before we move to it. We can accomplish this "
      "by selecting the views and spacing them out. You can do this very easily by zooming out. Simply hold your "
      "alt or option key and scroll down. Then select each shape and space them out. "
      "You can get back to the default view by pressing the \"1\" key."]]

    [:figure [:img {:src "/blog/interactive-layers/space-layers.png"}]]

    [:article.cta
     [:form {:method "post" :action "/duplicate/interactive-demo" :target "_blank"}
      [:input {:type "hidden" :name "__anti-forgery-token"
               :value ring.middleware.anti-forgery/*anti-forgery-token*}]
      [:button.blog-cta-button {:href "/"
                                     :role "button"
                                     :title "Make something."}
       "Reproduce this demo"]]])})
