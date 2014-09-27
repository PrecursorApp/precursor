(ns frontend.components.security
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.string :as str]
            [frontend.async :refer [put!]]
            [frontend.components.common :as common]
            [frontend.components.plans :as plans-component]
            [frontend.components.shared :as shared]
            [frontend.state :as state]
            [frontend.stefon :as stefon]
            [frontend.utils :as utils :include-macros true]
            [om.core :as om :include-macros true])
  (:require-macros [frontend.utils :refer [defrender html]]))

(defn hall-of-fame [app owner]
  (reify
    om/IRender
    (render [_]
      (html
       [:div.page.security
        [:div.banner
         [:div.container [:h1 "Security Researcher Hall of Fame"]]]
        [:div.container.content
         [:section
          [:p "We would like to thank the following people for responsibly disclosing security issues to us."]]
         [:ul
          [:li [:a {:href "https://twitter.com/exploitprotocol"} "Aditya Agrawal"] " (2014-04-07)"]
          [:li [:a {:href "http://kevinmccarthy.org/"} "Kevin McCarthy"] " (2014-04-07)"]
          [:li [:a {:href "https://twitter.com/gazly"} "J.m.Gazzaly"] " (2014-03-27)"]
          [:li [:a {:href "https://twitter.com/ashishbdhaduk"} "Ashishkumar B. Dhaduk"] " (2014-03-26)"]
          [:li [:a {:href "http://www.facebook.com/mtk911"} "Muhammad Talha Khan"] " (2014-03-26)"]
          [:li [:a {:href "https://www.randomstorm.com/"} "Scott Glossop"] " (2014-03-26)"]
          [:li [:a {:href "https://twitter.com/pranavvenkats"} "S.Venkatesh"] " (2014-03-26)"]
          [:li [:a {:href "https://twitter.com/NiteshShilpkar"} "Nitesh Kumar Shilpkar"] " (2014-03-25)"]
          [:li [:a {:href "https://twitter.com/OsandaMalith"} "Osanda Malith Jayathissa"] " (2014-03-21)"]
          [:li [:a {:href "https://www.facebook.com/junior.ns1de"} "Rodolfo Godalle, Jr."] " (2014-03-15)"]
          [:li [:a {:href "https://twitter.com/Silent_Screamr"} "Jayvardhan Singh"] " (2014-02-03)"]]
         [:p "To be included on this list, "
          [:a {:href "/security#disclosure"} "responsibly disclose"] " a security report to use, and provide adequate time to fix the issue. We'll link to your professional website, and send you a CircleCI t-shirt."]]]))))

(defn security [app owner]
  (reify
    om/IRender
    (render [_]
      (html
       [:div.page.security
        [:div.banner
         [:div.container
          [:h1 "Security policy"]
          [:h3 "If we lose your code, we're out of business."]]]
        [:div.container.content
         [:div.timestamp [:p [:strong "Last modified:"] " Mar 17, 2014"]]
         [:section
          [:h2 "Code security"]
          [:p
           "If Circle ever disclosed your code to an attacker, whether through our own error or through the error of one of our partners, we would very likely be out of business. So the security of your code base is not just important to us, it is essential to the survival of our company."]]
         [:section
          [:h3 "Circle staff doesn't read your code!"]
          [:p
           "In the normal course of events, Circle staff will never read your code! Occasionally, you might ask us for support, or to look into a problem you experience, in which it would be useful for our engineers to read your code. We will only do this if explicitly granted permission to do so as part of a support request, and will never do it otherwise. Outside of a support context, no human reads your code."]
          [:p
           "However, we occasionally do automated analysis of active projects to provide data over what features and services to provide next. We only analyze projects which are already being tested on Circle. For example, when looking at deployment, we ran scripts which told us the percentage of active projects which have "
           [:em "Capfiles"]
           " in their repositories."]
          [:p
           "Currently, only key employees have the ability to check out your code, including the founders and senior engineers. All of these employees are located in the US. To reiterate, we would only do so in response to a support request, with explicit permission. Contractors will never be given access to customer code."]
          [:p
           "Note that this is a change from our early policies, when we believed that customers wanted our help in setting up their CI with Circle. Early feedback, however, was unanimous: never ever read customer code unless asked to. So that is our rule!"]]
         [:section
          [:h3 "Our security model"]
          [:p
           "When we run your tests, we run them in a sandbox, meaning you are unable to access another customer's code, and they are unable to access yours. We never ever run any customer code in any context which is not sandboxed, or which allows access to other customer code."]
          [:p
           "Each sandbox is firewalled, and it is not possible to access a sandbox from another sandbox, or from the internet at large."]
          [:p
           "Circle's founders and employees are well versed in full-stack security. Paul worked on "
           [:a {:href "http://mozilla.org/"} "Mozilla's"]
           " Javascript engine, where every bug is a potential security bug; and was a principal engineer at "
           [:a {:href "http://mylookout.com"} "Lookout"] ", the premiere Android security company. Before that, he did his "
           [:a {:href "http://paulbiggar.com/research/#phd-dissertation"} "PhD in static analysis"] ", which kept him well versed with the latest security research and vulnerabilities. Allen was a lead developer at "
           [:a {:href "http://crossroads.com"} "Crossroads"] ", where he provided security audits for their full-stack SAN solution. His experience includes work on Storage Area Networks, Linux kernel drivers and medical imaging software, all of which have an extremely security-oriented focus."]
          [:p
           "We know what we're doing, and constantly question and audit our sensitive code, and solicit outside feedback about our security."]]
         [:section
          [:h3 "GitHub authorization"]
          [:p
           "To run your tests, we need to check out your code from GitHub. When you sign up for Circle, you tell GitHub that you are authorizing us to check out your private repositories. You may revoke this permission at any time through your "
           [:a
            {:href "https://github.com/settings/applications"}
            "GitHub application settings page"]
           " and by removing Circle's " [:em "Deploy Keys"] " and " [:em "Service Hooks"] " from your repositories' " [:em "Admin"] " pages."]
          [:p
           "While Circle allows you to selectively test your projects, note that GitHub's permissions model is \"all or nothing\" — Circle gets permission to access all of a user's repositories or none of them. We have asked GitHub to provide finer-grained permissions, but they have indicated it will not be completed in the short term. Please "
           [:a {:href "https://github.com/contact"} "contact GitHub"]
           " if this is important to you."]]
         [:section
          [:h3 "Canceling your account"]
          [:p
           "We do not currently have a button to delete your account, but we will be adding one soon. If you need your account canceled and your data deleted, please contact us at "
           [:a {:href "mailto:sayhi@circleci.com"} "sayhi@circleci.com"] ". To remove Circle's access to your GitHub account, you can remove it via your "
           [:a {:href "https://github.com/settings/applications"} "GitHub application settings page"] "."
           " You should also remove Circle's " [:em "Deploy Keys"] " and " [:em "Service Hooks"] " from your repositories' " [:em "Admin"] " pages."]]
         [:section
          [:h3 "Disclosure"]
          [:p
           "If you have found a vulnerability in CircleCi, please contact our security team by email at "
           [:a {:href "mailto:security@circleci.com"} "security@circleci.com"] ". We will do everything in our power to respond and fix the problem immediately. If possible, please encrypt your message using "
           [:a {:target "_blank", :href "/circleci-security.pub"}
            "our security team's GPG key"]
           " (ID: 0x4013DDA7, fingerprint: 3CD2 A48F 2071 61C0 B9B7  1AE2 6170 15B8 4013 DDA7)."]
          [:p
           "Upon discovering a vulnerability, we ask that you act in a way to protect our users' data:"]
          [:ul
           [:li "inform us as soon as possible,"]
           [:li
            "test against fake data and accounts, not our users' private data (please ask if you'd like a free account to work on this),"]
           [:li
            "work with us to close the vulnerability before disclosing it to others."]]
          [:p
           "We also maintain a "
           [:a
            {:href "/security/hall-of-fame"}
            "Security Researcher Hall of Fame"]
           " to thank individuals who have discovered vulnerabilities and worked with us to resolve them."]]
         [:section
          [:h3 "Partners with access to your source code"]
          [:p
           "Circle is built on Amazon EC2, and we check out your code onto Amazon's EC2 machines. If the EC2 service becomes vulnerable, your source code may also become vulnerable to accidental disclosure. "
           [:a
            {:href "http://aws.amazon.com/security/"}
            "Amazon's Security Center"]
           " discusses their security in great detail."]]
         [:section
          [:h3 "Other partners"]
          [:p
           "A small number of partners, who we choose not to enumerate for security reasons, have access to small amounts of our customer data. We constantly audit the data that is provided to them to ensure that this could not be used to gain access to your account or your code."]]
         [:section
          [:h3 "Feedback"]
          [:p
           "We take security incredibly seriously. If you have any suggestions for how we could improve our security, or improve this policy, please contact us at "
           [:a {:href "mailto:security@circleci.com"} "security@circleci.com"] ". We will act immediately to deal with the issue."]]
         [:h2 "Changelog"]
         [:ul.privacy-changelog
          [:li [:strong "Mar 17, 2014"] " Move the Security page to its own page"]
          [:li [:strong "Mar 15, 2014"] " Add a Hall of Fame."]
          [:li [:strong "Nov 5, 2013"] " Rotate GPG key."]
          [:li [:strong "Mar 10, 2013"] " Clarify that if asked to, and given explicit permission, we will look at your code to answer your support request."]
          [:li [:strong "Feb 8, 2013"] " Add disclosure section and GPG key."]
          [:li [:strong "Dec 22, 2012"] " We now have employees."]
          [:li [:strong "July 3, 2012"] " Clarified that only tested project are analyzed."]
          [:li [:strong "April 11, 2012"] " We now only enable individual projects when selected by users."]
          [:li [:strong "March 26, 2012"] " Initial version."]]
         [:div.center-text
          (shared/home-button {:source "security"} (om/get-shared owner [:comms :controls]))]]]))))
