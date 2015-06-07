(ns pc.views.email
  (:require [clj-time.core :as time]
            [clj-time.format]
            [clojure.string :as str]
            [hiccup.core :as hiccup]
            [pc.datomic.web-peer :as web-peer]
            [pc.http.urls :as urls]
            [pc.models.plan :as plan-model]
            [pc.profile :as profile]
            [pc.views.invoice :as invoice-view]))

(defn email-address
  ([local-part]
   (format "%s@%s" local-part (profile/prod-domain)))
  ([fancy-name local-part]
   (format "%s <%s>" fancy-name (email-address local-part))))

(defn format-inviter [inviter]
  (str/trim (str (:cust/first-name inviter)
                 (when (and (:cust/first-name inviter)
                            (:cust/last-name inviter))
                   (str " " (:cust/last-name inviter)))
                 " "
                 (cond (and (not (:cust/last-name inviter))
                            (:cust/first-name inviter))
                       (str "(" (:cust/email inviter) ") ")

                       (not (:cust/first-name inviter))
                       (str (:cust/email inviter) " ")

                       :else nil))))

(defn format-requester [requester]
  (let [full-name (str/trim (str (:cust/first-name requester)
                                 " "
                                 (when (:cust/first-name requester)
                                   (:cust/last-name requester))))]
    (str/trim (str full-name " "
                   (when-not (str/blank? full-name) "(")
                   (:cust/email requester)
                   (when-not (str/blank? full-name) ")")))))

(defn chat-invite-html [doc img-s3-url]
  (hiccup/html
   [:html
    [:body
     [:p
      "I'm prototyping something on Precursor, come join me at "
      [:a {:href (urls/from-doc doc)}
       (urls/from-doc doc)]
      "."]
     [:p "This is what I have so far:"]
     [:p
      [:a {:href (urls/from-doc doc)
           :style "display: inline-block"}
       [:img {:width 325
              :style "border: 1px solid #888888;"
              :alt "Images disabled? Just come and take a look."
              :src img-s3-url}]]]
     [:p {:style "font-size: 12px"}
      (format "Tell us if this message was sent in error %s." (email-address "info"))
      ;; Add some hidden text so that Google doesn't try to trim these.
      [:span {:style "display: none; max-height: 0px; font-size: 0px; overflow: hidden;"}
       " Sent at "
       (clj-time.format/unparse (clj-time.format/formatters :rfc822) (time/now))
       "."]]]]))

(defn document-access-grant-html [doc access-grant img-s3-url]
  (let [doc-link (urls/from-doc doc :query {:access-grant-token (:access-grant/token access-grant)})]
    (hiccup/html
     [:html
      [:body
       [:p
        "I'm prototyping something on Precursor, come join me at "
        [:a {:href doc-link}
         (urls/from-doc doc)]
        "."]
       [:p "This is what I have so far:"]
       [:p
        [:a {:href doc-link
             :style "display: inline-block"}
         [:img {:width 325
                :style "border: 1px solid #888888;"
                :alt "Images disabled? Just come and take a look."
                :src img-s3-url}]]]
       [:p {:style "font-size: 12px"}
        (format "Tell us if this message was sent in error %s." (email-address "info"))
        ;; Add some hidden text so that Google doesn't try to trim these.
        [:span {:style "display: none; max-height: 0px; font-size: 0px; overflow: hidden;"}
         " Sent at "
         (clj-time.format/unparse (clj-time.format/formatters :rfc822) (time/now))
         "."]]]])))

(defn team-access-grant-html [team access-grant]
  (let [subdomain (:team/subdomain team)
        intro-doc (:team/intro-doc team)]
    (hiccup/html
     [:html
      [:body
       [:p
        "You've been invited to the " subdomain " team on Precursor: "
        [:a {:href (urls/from-doc intro-doc :query {:access-grant-token (:access-grant/token access-grant)})}
         "Accept the invitation"]
        "."]
       [:p "When you create a new document in the " subdomain " subdomain, it will be private to your team by default. You'll also have access to all of the documents created by your team."]
       [:p {:style "font-size: 12px"}
        (format "Tell us if this message was sent in error %s." (email-address "info"))
        ;; Add some hidden text so that Google doesn't try to trim these.
        [:span {:style "display: none; max-height: 0px; font-size: 0px; overflow: hidden;"}
         " Sent at "
         (clj-time.format/unparse (clj-time.format/formatters :rfc822) (time/now))
         "."]]]])))

;; XXX: img-s3-url
(defn document-permission-grant-html [doc img-s3-url]
  (let [doc-link (urls/from-doc doc)]
    (hiccup/html
     [:html
      [:body
       [:p
        "I'm prototyping something on Precursor, come join me at "
        [:a {:href doc-link}
         doc-link]
        "."]
       [:p "This is what I have so far:"]
       [:p
        [:a {:href doc-link
             :style "display: inline-block"}
         [:img {:width 325
                :style "border: 1px solid #888888;"
                :alt "Images disabled? Just come and take a look."
                :src img-s3-url}]]]
       [:p {:style "font-size: 12px"}
        (format "Tell us if this message was sent in error %s." (email-address "info"))
        ;; Add some hidden text so that Google doesn't try to trim these.
        [:span {:style "display: none; max-height: 0px; font-size: 0px; overflow: hidden;"}
         " Sent at "
         (clj-time.format/unparse (clj-time.format/formatters :rfc822) (time/now))
         "."]]]])))

(defn team-permission-grant-html [team]
  (let [subdomain (:team/subdomain team)
        intro-doc (:team/intro-doc team)]
    (hiccup/html
     [:html
      [:body
       [:p
        "You've been added to the " subdomain " team on Precursor. "
        [:a {:href (urls/from-doc intro-doc)}
         (urls/root :subdomain subdomain)]
        "."]
       [:p "When you create a new document in the " subdomain " subdomain, it will be private to your team by default."
        " You'll also have access to all of the documents created by your team."]
       [:p {:style "font-size: 12px"}
        (format "Tell us if this message was sent in error %s." (email-address "info"))
        ;; Add some hidden text so that Google doesn't try to trim these.
        [:span {:style "display: none; max-height: 0px; font-size: 0px; overflow: hidden;"}
         " Sent at "
         (clj-time.format/unparse (clj-time.format/formatters :rfc822) (time/now))
         "."]]]])))


(defn document-access-request-html [doc requester]
  (let [doc-link (urls/from-doc doc)]
    (hiccup/html
     [:html
      [:body
       [:p (str (format-requester requester) " wants access to one of your documents on Precursor.")]
       [:p "Go to the "
        [:a {:href (urls/from-doc doc :query {:overlay "sharing"})}
         "manage permissions page"]
        " to grant or deny access."]
       [:p {:style "font-size: 12px"}
        (format "Tell us if this message was sent in error %s." (email-address "info"))
        ;; Add some hidden text so that Google doesn't try to trim these.
        [:span {:style "display: none; max-height: 0px; font-size: 0px; overflow: hidden;"}
         " Sent at "
         (clj-time.format/unparse (clj-time.format/formatters :rfc822) (time/now))
         "."]]]])))

(defn team-access-request-html [team requester]
  (let [subdomain (:team/subdomain team)
        intro-doc (:team/intro-doc team)]
    (hiccup/html
     [:html
      [:body
       [:p (str (format-requester requester) " wants to join the " subdomain " team on Precursor.")]
       [:p "Go to the "
        [:a {:href (urls/from-doc intro-doc :query {:overlay "team-settings"})}
         "manage permissions page"]
        " to grant or deny access."]
       [:p {:style "font-size: 12px"}
        (format "Tell us if this message was sent in error %s." (email-address "info"))
        ;; Add some hidden text so that Google doesn't try to trim these.
        [:span {:style "display: none; max-height: 0px; font-size: 0px; overflow: hidden;"}
         " Sent at "
         (clj-time.format/unparse (clj-time.format/formatters :rfc822) (time/now))
         "."]]]])))

(defn early-access-html [cust]
  (let [cust-name (or (:cust/name cust)
                      (:cust/first-name cust))]
    (hiccup/html
     [:html
      [:body
       (when (seq cust-name)
         [:p (format "Hi %s," cust-name)])
       [:p
        "You've been granted early access to Precursor's paid features."]
       [:p
        "You can now create private documents and control who has access to them. "
        "Let the rest of your team create private docs by having them click the request "
        "access button and filling out the same form you did."]

       [:p
        "You'll have two weeks of free, unlimited early access, and then we'll follow "
        "up with you to see how things are going."]

       [:p
        "Next, "
        [:a {:title "Private docs early access"
             :href (urls/blog-url "private-docs-early-access")}
         "learn to use private docs"]
        " or "
        [:a {:title "Launch Precursor"
             :href (urls/root)}
         "make something on Precursor"]
        "."]
       [:p {:style "font-size: 12px"}
        (format "Tell us if this message was sent in error %s." (email-address "info"))
        ;; Add some hidden text so that Google doesn't try to trim these.
        [:span {:style "display: none; max-height: 0px; font-size: 0px; overflow: hidden;"}
         " Sent at " (clj-time.format/unparse (clj-time.format/formatters :rfc822) (time/now)) "."]]]])))

(defn invoice-html [team invoice]
  (hiccup/html
   [:html
    [:body
     [:p "You have a new invoice for the " (:team/subdomain team) " team."]

     [:p "Invoice #" (web-peer/client-id invoice)]

     [:table {:border false}
      [:tbody
       [:tr
        [:td "Date"]
        [:td (invoice-view/format-invoice-date (:invoice/date invoice))]]
       [:tr
        [:td "Period"]
        [:td (str (invoice-view/format-invoice-date (:invoice/period-start invoice))
                  " to "
                  (invoice-view/format-invoice-date (:invoice/period-end invoice)))]]
       [:tr
        [:td "Description"]
        [:td (:invoice/description invoice "Team subscription")]]

       (when (:discount/coupon invoice)
         (let [coupon (plan-model/coupon-read-api (:discount/coupon invoice))]
           [:tr
            [:td "Discount"]
            [:td (format "%s%% off for %s months"
                         (:coupon/percent-off coupon)
                         (:coupon/duration-in-months coupon))]]))

       [:tr
        [:td "Total Charge"]
        [:td (invoice-view/format-stripe-cents (:invoice/total invoice))]]

       (when (neg? (:invoice/total invoice))
         [:tr
          [:td "Credit"]
          [:td (invoice-view/format-stripe-cents (- (:invoice/total invoice)))]])]]

     [:p
      "You can modify your plan on "
      [:a {:href (urls/from-doc (:team/intro-doc team)
                                   :query {:overlay "plan"})}
       "your team's plan page"]
      "."]

     [:p "If you have any questions, please respond to this email."]

     [:p {:style "font-size: 12px"}
      (format "Tell us if this message was sent in error %s." (email-address "info"))
      ;; Add some hidden text so that Google doesn't try to trim these.
      [:span {:style "display: none; max-height: 0px; font-size: 0px; overflow: hidden;"}
       " Sent at " (clj-time.format/unparse (clj-time.format/formatters :rfc822) (time/now)) "."]]]]))
