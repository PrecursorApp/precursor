(ns pc.email
  (:require [clojure.string :as str]
            [clj-time.core :as time]
            [clj-time.format]
            [hiccup.core :as hiccup]
            [pc.mailgun :as mailgun]))

(defn chat-invite-html [doc-id]
  (hiccup/html
   [:html
    [:body
     [:p
      "I'm prototyping something on Precursor, come join me at "
      [:a {:href (str "https://prcrsr.com/document/" doc-id)}
       (str "https://prcrsr.com/document/" doc-id)]
      "."]
     [:p "This is what I have so far:"]
     [:p
      [:a {:href (str "https://prcrsr.com/document/" doc-id)
           :style "display: inline-block"}
       [:img {:width 325
              :style "border: 1px solid #888888;"
              :alt "Images disabled? Just come and take a look."
              :src (str "https://prcrsr.com/document/" doc-id ".png?rand=" (rand))}]]]
     [:p {:style "font-size: 12px"}
      "Tell us if this message is an error info@prcrsr.com."
      ;; Add some hidden text so that Google doesn't try to trim these.
      [:span {:style "display: none; max-height: 0px; font-size: 0px; overflow: hidden;"}
       " Sent at "
       (clj-time.format/unparse (clj-time.format/formatters :rfc822) (time/now))
       "."]]]]))

(defn send-chat-invite [{:keys [cust to-email doc-id]}]
  (mailgun/send-message {:from "Precursor <joinme@prcrsr.com>"
                         :to to-email
                         :subject (str/trim (str (:cust/first-name cust)
                                                 (when (and (:cust/first-name cust)
                                                            (:cust/last-name cust))
                                                   (str " " (:cust/last-name cust)))
                                                 " "
                                                 (cond (and (not (:cust/last-name cust))
                                                            (:cust/first-name cust))
                                                       (str "(" (:cust/email cust) ") ")

                                                       (not (:cust/first-name cust))
                                                       (str (:cust/email cust) " ")

                                                       :else nil)
                                                 "invited you to a document on Precursor"))
                         :text (str "Hey there,\nCome draw with me on Precursor: https://prcrsr.com/document" doc-id)
                         :html (chat-invite-html doc-id)
                         :o:tracking "yes"
                         :o:tracking-opens "yes"
                         :o:tracking-clicks "no"
                         :o:campaign "chat_invites"}))
