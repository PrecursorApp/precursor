(ns pc.views.blog.ideas-are-made-with-precursor
  (:require [ring.middleware.anti-forgery :refer (wrap-anti-forgery)]
            [pc.http.urls :as urls]
            [pc.views.email :as email]
            [pc.profile :as profile]
            [pc.views.common :refer (cdn-path)]
            [pc.views.blog.common :as common]))

(defn demo [placeholder gif & {:keys [caption]}]
  [:div.play-gif {:alt "demo"
                     :onmouseover (format "this.getElementsByTagName('img')[0].src = '%s'" gif)
                     :ontouchstart (format "this.getElementsByTagName('img')[0].src = '%s'" gif)
                     :onmouseout (format "this.getElementsByTagName('img')[0].src = '%s'" placeholder)
                     :ontouchend (format "this.getElementsByTagName('img')[0].src = '%s'" placeholder)}
   [:a (when caption
         {:data-caption-black caption})
    [:img {:src placeholder}]]])

(defn demo-with-blank-canvas [gif caption]
  (demo (cdn-path "/blog/private-docs-early-access/canvas.png") gif :caption caption))

(def dribbble
  [:i {:class "icon-dribbble"}
   [:svg {:class "iconpile" :viewBox "0 0 100 100"}
    [:path {:class "fill-dribbble" :d "M50,99.9C22.4,99.9,0,77.5,0,50C0,22.5,22.4,0.1,50,0.1 c27.6,0,50,22.4,50,49.9C100,77.5,77.6,99.9,50,99.9L50,99.9z M92.2,56.8c-1.5-0.5-13.2-4-26.6-1.8c5.6,15.3,7.9,27.8,8.3,30.4 C83.4,79,90.3,68.7,92.2,56.8L92.2,56.8z M66.7,89.3C66,85.6,63.6,72.5,57.6,57c-0.1,0-0.2,0.1-0.3,0.1 c-24.1,8.4-32.7,25.1-33.5,26.6c7.2,5.6,16.3,9,26.2,9C55.9,92.7,61.6,91.5,66.7,89.3L66.7,89.3z M18.3,78.6 c1-1.7,12.7-21,34.7-28.1c0.6-0.2,1.1-0.3,1.7-0.5c-1.1-2.4-2.2-4.8-3.5-7.2c-21.3,6.4-42,6.1-43.9,6.1c0,0.4,0,0.9,0,1.3 C7.3,61,11.5,71,18.3,78.6L18.3,78.6z M8.2,41.3c1.9,0,19.5,0.1,39.5-5.2C40.6,23.6,33,13,31.8,11.5C19.9,17.1,11,28.1,8.2,41.3 L8.2,41.3z M40,8.6c1.2,1.6,8.9,12.1,15.9,25c15.2-5.7,21.6-14.3,22.4-15.4C70.8,11.5,60.9,7.4,50,7.4C46.6,7.4,43.2,7.8,40,8.6 L40,8.6z M83.1,23.1c-0.9,1.2-8.1,10.4-23.8,16.8c1,2,1.9,4.1,2.8,6.2c0.3,0.7,0.6,1.5,0.9,2.2c14.2-1.8,28.3,1.1,29.7,1.4 C92.6,39.6,89,30.4,83.1,23.1L83.1,23.1z"}]]])

(def dribbble-card
  [:div#vendor.dribbble-card
   [:div.dribbble-head
    [:div.dribbble-stats
     [:div.dribbble-stat
      [:strong "176"]
      [:span " Shots"]]
     [:div.dribbble-stat
      [:strong "43,502"]
      [:span " Followers"]]]
    [:div.dribbble-photo
     [:a {:href "#"}
      [:img.dribbble-avatar {:src "https://d13yacurqjgara.cloudfront.net/users/14268/avatars/normal/me.jpg?1383933441"}]]]
    [:div.dribbble-follow
     [:a.dribbble-button {:href "#"}
      dribbble
      [:strong "Follow"]]]]
   [:div.dribbble-body
    [:div.dribbble-info
     [:a.dribbble-name {:href "#"}
      [:strong "Eddie Lobanovskiy"]]]
    [:div.dribbble-link
     [:a.dribbble-site {:href "#"}
      [:span "unfold.co"]]]]
   ; [:a.img {:data-caption-white "I pile icons on top of each other, and then I toggle their visibility to compare."}
   ;  [:img {:src "https://d13yacurqjgara.cloudfront.net/users/33274/screenshots/1988862/putnam-sketch2.gif"}]]
   (demo-with-blank-canvas (cdn-path "/blog/private-docs-early-access/grant-access.gif") "Grant someone access with their email address.")])

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

    [:article
     [:h3 "Dave was here."]
     dribbble-card]

    ; [:article
    ;  [:h3 "Why so serious?"]
    ;  dribbble-card]

    [:article
     [:h3 "What's with the anonymity?"]
     [:p "Precursor is free for everyone to use. "
         "That even includes users who don't sign up, so we get a lot of anonymous illustrations. "
         "If one of these belongs to you send me an email. "
         "The attribution will get updated and I'll even send you a custom t-shirt to say thanks. "]]

    [:figure
     (demo-with-blank-canvas (cdn-path "/blog/private-docs-early-access/grant-access.gif") "Grant someone access with their email address.")]

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

    [:figure
     (demo-with-blank-canvas (cdn-path "/blog/private-docs-early-access/grant-access.gif") "Grant someone access with their email address.")]

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
