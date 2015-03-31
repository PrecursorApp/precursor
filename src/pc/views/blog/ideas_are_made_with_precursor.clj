(ns pc.views.blog.ideas-are-made-with-precursor
  (:require [ring.middleware.anti-forgery :refer (wrap-anti-forgery)]
            [pc.http.urls :as urls]
            [pc.views.email :as email]
            [pc.profile :as profile]
            [pc.views.common :refer (cdn-path)]
            [pc.views.blog.common :as common]))

(def logo-github
  [:i {:class "icon-github"}
   [:svg {:class "iconpile" :viewBox "0 0 100 100"}
    [:path {:class "fill-github" :d "M50,0C22.4,0,0,22.4,0,50c0,22.1,14.3,40.8,34.2,47.4 c2.5,0.5,3.4-1.1,3.4-2.4c0-1.2,0-4.3-0.1-8.5c-13.9,3-16.8-6.7-16.8-6.7c-2.3-5.8-5.6-7.3-5.6-7.3c-4.5-3.1,0.3-3,0.3-3 c5,0.4,7.7,5.2,7.7,5.2c4.5,7.6,11.7,5.4,14.6,4.2c0.5-3.2,1.7-5.4,3.2-6.7c-11.1-1.3-22.8-5.6-22.8-24.7c0-5.5,1.9-9.9,5.1-13.4 c-0.5-1.3-2.2-6.3,0.5-13.2c0,0,4.2-1.3,13.7,5.1c4-1.1,8.3-1.7,12.5-1.7c4.2,0,8.5,0.6,12.5,1.7c9.5-6.5,13.7-5.1,13.7-5.1 c2.7,6.9,1,12,0.5,13.2c3.2,3.5,5.1,8,5.1,13.4c0,19.2-11.7,23.4-22.8,24.7c1.8,1.5,3.4,4.6,3.4,9.3c0,6.7-0.1,12.1-0.1,13.7 c0,1.3,0.9,2.9,3.4,2.4C85.7,90.8,100,72.1,100,50C100,22.4,77.6,0,50,0z"}]]])

(defn card-github [username document name img stat]
  (let [animated    (str "https://dl.dropboxusercontent.com/u/1212676/madewithprecursor/" username ".gif")
        placeholder (str "https://dl.dropboxusercontent.com/u/1212676/madewithprecursor/" username "-placeholder.gif")
        swap-img    "this.getElementsByTagName('img')[0].src = '%s'"]
    [:div.card
     [:a.card-doc.free-border {:href (str "https://precursorapp.com" "/document/" document)
                               :target "_blank"
                               :onmouseover  (format swap-img animated)
                               :ontouchstart (format swap-img animated)
                               :onmouseout   (format swap-img placeholder)
                               :ontouchend   (format swap-img placeholder)}
      [:img {:src placeholder}]]
     [:div.card-maker.free-border.card-maker-github
      [:div.card-maker-head.free-border
       [:div.card-top.free-border
        [:div.card-stat
         [:span stat]]]
       [:div.card-photo
        [:a {:href (str "https://github.com/" username)}
         [:img.card-avatar {:src img}]]]
       [:div.card-top.free-border
        [:a.card-follow {:href (str "https://github.com/" username)}
         logo-github
         [:span "Follow"]]]]
      [:div.card-maker-body
       [:div.card-name name]
       [:div.card-link
        [:a {:href (str "https://github.com/" username)}
         [:span (str "@" username)]]]]]]))

(defn ideas-are-made-with-precursor []
  {:title "Ideas are made with Precursor."
   :blurb "I'm exceedingly curious to see how people use Precursor. Recently we've been using an internal playback tool to learn about this..."
   :author "Danny"
   :image ""
   :body
   (list
    [:article
     [:p "As our user base continues to grow, I'm exceedingly curious to see how people use Precursor. "
         "Recently we've been using an internal playback tool to learn about this. "]
     [:p "Today I'm excited to announce that we're now making this tool accessible to everyone. "
         "I've curated the following examples to showcase some of its key uses. "]]

    [:article
     [:h3 "Keep moving forward."]
     [:p  "This doc is a unique glimpse at the discipline it takes to be a great designer. "
          "Eddie is well known for his exceptional work, and rather than waste time with elaborate sketches, he quickly opts for a simple placeholder to get the job done. "]]
    [:figure (common/card "lobanovskiy" "17592197312062?cx=1044&cy=580&z=0.45&replay=true")]

    [:article
     [:h3 "Why so serious?"]
     [:p  "I like this illustration because it reminds me that work should be fun. "
          "I also find it interesting how much detail Damir can achieve with each stroke. "]]
    [:figure (common/card "dhotlo2" "17592197311947?cx=629&cy=376&z=1&replay=true")]

    [:article
     [:h3 "Minimum viable prototype."]
     [:p  "This is something I'm seeing more people practice on Precursor—prototypes don't need every detail. "
          "Watch Faith contemplate the value of that avatar, explore the idea, and ultimately remove it. "]]
    [:figure (common/card "fatihturan" "17592197311774?cx=707&cy=502&z=1&replay=true")]

    [:article
     [:h3 "Iteration is important."]
     [:p  "Without watching Justin build this prototype, it's hard to tell how many iterations we through. "
          "Too many can be just as dangerous as too few, but I think this strikes a good balance. "]]
    [:figure (common/card "jyo208" "17592197310592?cx=584&cy=581&z=0.55&replay=true")]

    [:article
     [:h3 "Set it up."]
     [:p  "Before Ken tried drawing any components, he got his content and spacing figured out. "
          "Isolating important areas of your ideas is crucial for expressing them quickly. "]]
    [:figure (common/card "kenseals" "17592197311539?cx=517&cy=392&z=1&replay=true&tx-count=59")]

    [:article
     [:h3 "That was fast."]
     [:p  "Peter made short work of this map by staying focused on his content—a great strategy for diagramming. "
          "He figured out alignment and connections afterwards. "]]
    [:figure (card-github "peteratticusberg" "17592197310304?cx=687&cy=636&z=0.8&replay=true" "Peter Berg" "https://avatars0.githubusercontent.com/u/3895824" "11 repos")]

    [:article
     [:h3 "Avoid the tedious."]
     [:p  "Mathieu used duplication to skip over the tedious areas of his idea. "
          "He prioritized the unique aspects of his prototype and automated the others. "]]
    [:figure (common/card "tooks_" "17592197347828?cx=982&cy=974&z=0.45&replay")]

    [:article
     [:h3 "I'd wear that."]
     [:p  "To thank these users for letting me show their work, I'm sending them all custom t-shirts displaying their prototypes. "
          "I want to do this again soon, so if you make something cool, let me know. "
          "Email "
          [:a {:href (format "mailto:%s" (email/email-address "hi"))} "hi@precursorapp.com"]
          " with a link and if it gets featured, you'll get a one-of-a-kind t-shirt! "]]
    [:figure [:img {:src "https://dl.dropboxusercontent.com/u/1212676/madewithprecursor/eddie-shirt.png"}]]

    [:article.blogpost-author
     [:p
      "Written by "
      [:a {:href "https://twitter.com/dannykingme"} "Danny King"]
      "."]]

    )})
