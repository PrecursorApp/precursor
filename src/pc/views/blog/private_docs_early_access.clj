(ns pc.views.blog.private-docs-early-access
  (:require [ring.middleware.anti-forgery :refer (wrap-anti-forgery)]
            [pc.email :as email]
            [pc.views.common :refer (cdn-path)]
            [pc.views.blog.common :as common]))


(defn demo [placeholder gif & {:keys [caption]}]
  [:figure.play-gif {:alt "demo"
                     :onmouseover (format "this.getElementsByTagName('img')[0].src = '%s'" gif)
                     :ontouchstart (format "this.getElementsByTagName('img')[0].src = '%s'" gif)
                     :onmouseout (format "this.getElementsByTagName('img')[0].src = '%s'" placeholder)
                     :ontouchend (format "this.getElementsByTagName('img')[0].src = '%s'" placeholder)}
   [:a (when caption
         {:data-caption-black caption})
    [:img {:src placeholder}]]])

(defn demo-with-blank-canvas [gif caption]
  (demo (cdn-path "/blog/private-docs-early-access/canvas.png") gif :caption caption))

(defn private-docs-early-access []
  {:title "Private docs early access."
   :blurb "How to access and use our new privacy settings"
   :author "Danny"
   :body
   (list
    [:article
     [:p "These are a few questions we thought users might ask while testing private docs.
          If you think of any other questions that might be useful, "
      [:a {:href (format "mailto:%s" (email/email-address "hi"))} "let us know"]
      " and we'll throw it in."]]

    [:article
     [:h3 "How do I make a doc private?"]
     [:p "Open the side menu with the button in the upper-left corner and then select \"Sharing\".
         At the bottom of the sharing menu, hover over the Private/Public toggle to expand it, then select \"Private\".
         The owner of the doc can toggle between public and private at anytime from this menu."]]
    (demo-with-blank-canvas (cdn-path "/blog/private-docs-early-access/make-private.gif") "Make a doc private by toggling the privacy setting at the bottom of the sharing menu.")

    [:article
     [:h3 "How do I grant access to my private doc?"]
     [:p "After the doc is made private, the creator of the doc will be the only user allowed to view its contents.
         To grant access to other users, simply enter their email in the input field near the top of the sharing menu and submit it with enter.
         That user will then be displayed in a list below the input field."]]
    (demo-with-blank-canvas (cdn-path "/blog/private-docs-early-access/grant-access.gif") "Grant someone access with their email address.")

    [:article
     [:h3 "How do I approve access to my private doc?"]
     [:p "When someone requests access to your private doc, they will show up in a list inside the sharing menu.
         From this menu you can approve or deny requests by hovering over them and clicking either the check or the x icon.
         If you deny someone by mistake, you will still have the option to approve them."]]
    (demo-with-blank-canvas (cdn-path "/blog/private-docs-early-access/approve-access.gif") "Approve or deny access requests from the sharing menu.")

    [:article
     [:h3 "How do I request access to a private doc?"]
     [:p "When other users arrive at the url for a private doc they will find an empty canvas and a prompt explaining that the doc is private.
         That prompt has a button which allows them to request access from the owner."]]

    (demo-with-blank-canvas (cdn-path "/blog/private-docs-early-access/request-access.gif") "Request access by going to the url and following the menu prompt.")

    [:article
     [:h3 "What kind of feedback should I share?"]
     [:p "There's a lot left to build for team features and private docs, but this is a good start.
         We'd love to hear your feedback on everything that's working well and anything that could be improved.
         The earlier we find bugs and design flaws, the easier they will be to fix."]]

    [:article
     [:h3 "Should I let you know if my private docs feel secure?"]
     [:p "Absolutely! Our primary goal with private docs is to keep user data safe.
         We've received feedback from many users that they love using Precursor at home, but to use it at work they need a private version.
         These team features are a direct response to that feedback.
         We want to offer peace of mind so users can focus entirely on their ideas."]]

    [:article
     [:h3 "What if I need help?"]
     [:p "When you make a document private, its contents are hidden to every user that is not granted access, including the Precursor team.
         Ping us in chat with @prcrsr if you need help with something and we'll request access."]]

    [:article
     [:h3 "How will team features work going forward?"]
     [:p "Soon we'll be offering these features to teams using a premium subscription model.
         Precursor provides simplicity to an otherwise complicated market, and we want our pricing model to reflect that.
         We're aiming to start these plans at $10 per user, per month.
         Please let us know how you feel about this price, we're excited to hear your feedback!"]]
    )})
