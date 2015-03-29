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

(defn featured [username document]
  [:figure
   (common/dribbble-card username)
   (let [animation   (cdn-path (str "/blog/ideas-are-made-with-precursor/" username ".gif"))
         placeholder (cdn-path (str "/blog/ideas-are-made-with-precursor/" username "-placeholder.gif"))]
     [:div.gif-shot {:data-content "Preview."
                     :onmouseover  (format "this.getElementsByTagName('img')[0].src = '%s'" animation)
                     :ontouchstart (format "this.getElementsByTagName('img')[0].src = '%s'" animation)
                     :onmouseout   (format "this.getElementsByTagName('img')[0].src = '%s'" placeholder)
                     :ontouchend   (format "this.getElementsByTagName('img')[0].src = '%s'" placeholder)}
    [:a {:href (str "https://precursorapp.com" "/document/" document)
         :data-caption "Remake."}
     [:img {:src placeholder}]]])])

(defn ideas-are-made-with-precursor []
  {:title "Ideas are made with Precursor."
   :blurb "What we found most interesting about the Product Hunt traffic was not its volume, but its quality of feedback..."
   :author "Danny"
   :image ""
   :body
   (list
    [:article
     [:p "Precursor is a collaboration web app that makes prototyping effortless. "
         "Although, lately I've noticed a lot of users ignore that last part. "
         "There are fascinating things being made with Precursor everyday. "
         "I even put together a list of my favorites. "]]

    (featured "lobanovskiy" "17592197312062?cx=1044&cy=580&z=0.45&replay=true")
    (featured "dhotlo2"     "17592197311947?cx=629&cy=376&z=1&replay=true")
    (featured "fatihturan"  "17592197311774?cx=707&cy=502&z=1&replay=true")
    (featured "kenseals"    "17592197311539?cx=517&cy=392&replay=true&tx-count=59&z=1")
    (featured "jyo208"      "17592197310592?cx=584&cy=581&z=0.55&replay=true")

    [:figure
     [:div#vendor.dribbble-card
      [:div.dribbble-head
       [:div.dribbble-stats
        [:div.dribbble-stat
         [:strong "0"]
         [:span " Followers"]]]
       [:div.dribbble-photo
        [:a {:href "https://github.com/peteratticusberg"}
         [:img.dribbble-avatar {:src "https://avatars0.githubusercontent.com/u/3895824"}]]]
       [:div.dribbble-follow
        [:a.dribbble-button {:href "https://github.com/peteratticusberg"}
         logo-github
         [:strong "Follow"]]]]
      [:div.dribbble-body
       [:div.dribbble-info
        [:a.dribbble-name {:href "https://github.com/peteratticusberg"}
         [:strong "Peter Berg"]]]
       [:div.dribbble-link
        [:span "New York"]]]]
     (let [animation   (cdn-path (str "/blog/ideas-are-made-with-precursor/peteratticusberg.gif"))
           placeholder (cdn-path (str "/blog/ideas-are-made-with-precursor/peteratticusberg-placeholder.gif"))]
       [:div.gif-shot {:data-content "Preview."
                       :onmouseover  (format "this.getElementsByTagName('img')[0].src = '%s'" animation)
                       :ontouchstart (format "this.getElementsByTagName('img')[0].src = '%s'" animation)
                       :onmouseout   (format "this.getElementsByTagName('img')[0].src = '%s'" placeholder)
                       :ontouchend   (format "this.getElementsByTagName('img')[0].src = '%s'" placeholder)}
      [:a {:href "https://precursorapp.com/document/17592197310304?cx=687&cy=636&z=0.8&replay=true"
           :data-caption "Remake."}
       [:img {:src placeholder}]]])]

    [:article
     [:h3 "What's with the anonymity?"]
     [:p "Precursor is free for everyone to use. "
         "That even includes users who don't sign up, so we get a lot of anonymous illustrations. "
         "If one of these belongs to you send me an email. "
         "The attribution will get updated and I'll even send you a custom t-shirt to say thanks. "]]

    [:figure [:img {:src (cdn-path "/blog/ideas-are-made-with-precursor/eddie-shirt.png")}]]

    [:article
     [:p "And by \"custom\" I mean the t-shirt will have your design on it. "
         "I plan on doing more features like this in the future. "
         "If you make something cool with Precursor email "
         [:a {:href "#"} "danny@precursorapp.com"]
         " with your mailing address and a link to the doc. "
         "If it gets featured in the next post I'll ship you a one-of-a-kind t-shirt!"]]

    [:article
     [:h3 "I tried making one of my own."]
     [:p "We implemented a new feature recently that lets users play back interactions that happened in their doc. "
         "The main utility in this feature will come later, but in the meantime we thought it would be fun to play with in groups. "
         "And if we're trying to show it off to a group, why not target a huge group? "
         "Like Reddit, perhaps? "]]

    [:figure [:img {:src "https://placehold.it/800x600"}]]

    [:article
     [:h3 "That's it for now."]
     [:p "I had a lot of fun finding all of this cool stuff, and I didn't even get to showcase everything."
         "I'm hoping to do this again soon, but it does take a considerable amount of time to sort through thousands of these. "
         "You can help by sending me tips for amazing docs to take a look at. "
         "So if you or your teammate make a really cool looking wireframe, illustration, etc. this week, tell me about it! "]]

    ; [:figure
    ;  [:a.img {:data-caption-black "I pile icons on top of each other, and then I toggle their visibility to compare."}
    ;   [:img {:src (cdn-path "/blog/clojurescript-is-a-design-tool/illustrator-onoff.gif")}]]]

    )})
