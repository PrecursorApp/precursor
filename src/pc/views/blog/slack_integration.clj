(ns pc.views.blog.slack-integration
  (:require [pc.views.blog.common :as common]
            [pc.views.common :refer (cdn-path external-cdn-path)]))

(defn slack-integration []
  {:title "Slack Integration"
   :blurb "Coordinate your collaboration with Precursor's Slack integration"
   :author "Daniel"
   :image (external-cdn-path "/blog/slack-integration/slack-preview.png")
   :body
   (list
    [:article
     [:p [:a {:href "https://precursorapp.com"
              :title "Precursor is a sketching tool for teams with real-time collaboration."}
          "Precursor"]
      " has our first integration with Slack! Slack is messaging for teams. We use and recommend them to anyone who asks. Slack is simply the best chat app out there."]
     [:p "You can use our Slack integration to post one of your team's docs to your Slack channel. It's a great way to quickly get everyone in the same Precursor doc. Of course, you can still share the url or export from the menu in the top left."]]

    [:figure
     [:a.img {:data-caption-black "Get everyone in your Slack channel in the same doc"}
      [:img {:src (external-cdn-path "/blog/slack-integration/slack-preview.png")}]]]


    [:article
     [:p "To set up your Slack hook, open up the team menu in the upper right, then navigate to \"Post to Slack\". Click on the link to go to Slack's integrations page, scroll to the end and add a new incoming webhook. Choose a channel, create the hook, then paste the webhook url into Precursor's form and save it."]
     [:p "Click on the channel name to post the current doc to Slack and add an optional message."]]

    [:article
     [:p [:a {:href "/pricing"} "Start a team trial"]
      " to try out our Slack integration."]])})
