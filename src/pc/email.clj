(ns pc.email
  (:require [clj-time.core :as time]
            [clj-time.format]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [hiccup.core :as hiccup]
            [pc.datomic :as pcd]
            [pc.http.urls :as urls]
            [pc.ses :as ses]
            [pc.models.access-grant :as access-grant-model]
            [pc.models.permission :as permission-model]
            [pc.models.cust :as cust-model]
            [pc.models.doc :as doc-model]
            [pc.profile :as profile]
            [pc.utils]
            [slingshot.slingshot :refer (throw+ try+)]))

(defn email-address
  ([local-part]
   (format "%s@%s" local-part (profile/prod-domain)))
  ([fancy-name local-part]
   (format "%s <%s>" fancy-name (email-address local-part))))

(defn emails-to-send [db eid]
  (set (map first
            (d/q '{:find [?emails]
                   :in [$ ?e]
                   :where [[?e :needs-email ?email-eid]
                           [?email-eid :db/ident ?emails]]}
                 db eid))))

(defn sent-emails [db eid]
  (set (map first
            (d/q '{:find [?emails]
                   :in [$ ?e]
                   :where [[?e :sent-email ?email-eid]
                           [?email-eid :db/ident ?emails]]}
                 db eid))))

(defn mark-sent-email
  "Returns true if this was the first transaction to mark the email as sent. False if it wasn't."
  [eid email-enum]
  (let [txid (d/tempid :db.part/tx)
        t @(d/transact (pcd/conn) [{:db/id txid
                                    :transaction/source :transaction.source/mark-sent-email}
                                   [:db/retract eid :needs-email email-enum]
                                   [:db/add eid :sent-email email-enum]])]
    (and (not (contains? (sent-emails (:db-before t) eid) email-enum))
         (contains? (sent-emails (:db-after t) eid) email-enum))))

(defn unmark-sent-email
  [eid email-enum]
  (let [txid (d/tempid :db.part/tx)]
    @(d/transact (pcd/conn) [{:db/id txid
                              :transaction/source :transaction.source/unmark-sent-email}
                             [:db/add eid :needs-email email-enum]
                             [:db/retract eid :sent-email email-enum]])))

(defn chat-invite-html [doc-id]
  (hiccup/html
   [:html
    [:body
     [:p
      "I'm prototyping something on Precursor, come join me at "
      [:a {:href (urls/doc doc-id)}
       (urls/doc doc-id)]
      "."]
     [:p "This is what I have so far:"]
     [:p
      [:a {:href (urls/doc doc-id)
           :style "display: inline-block"}
       [:img {:width 325
              :style "border: 1px solid #888888;"
              :alt "Images disabled? Just come and take a look."
              :src (urls/doc-png doc-id :query {:rand (rand)})}]]]
     [:p {:style "font-size: 12px"}
      (format "Tell us if this message was sent in error %s." (email-address "info"))
      ;; Add some hidden text so that Google doesn't try to trim these.
      [:span {:style "display: none; max-height: 0px; font-size: 0px; overflow: hidden;"}
       " Sent at "
       (clj-time.format/unparse (clj-time.format/formatters :rfc822) (time/now))
       "."]]]]))

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

(defn send-chat-invite [{:keys [cust to-email doc-id]}]
  (ses/send-message {:from (email-address "Precursor" "joinme")
                     :to to-email
                     :subject (str (format-inviter cust)
                                   " invited you to a document on Precursor")
                     :text (str "Hey there,\nCome draw with me on Precursor: " (urls/doc doc-id))
                     :html (chat-invite-html doc-id)
                     :o:tracking "yes"
                     :o:tracking-opens "yes"
                     :o:tracking-clicks "no"
                     :o:campaign "chat_invites"}))

(defn access-grant-html [doc-id access-grant image-permission]
  (let [doc-link (urls/doc doc-id :query {:access-grant-token (:access-grant/token access-grant)})
        image-link (urls/doc-png doc-id :query {:rand (rand) :auth-token (:permission/token image-permission)})]
    (hiccup/html
     [:html
      [:body
       [:p
        "I'm prototyping something on Precursor, come join me at "
        [:a {:href doc-link}
         (urls/doc doc-id)]
        "."]
       [:p "This is what I have so far:"]
       [:p
        [:a {:href doc-link
             :style "display: inline-block"}
         [:img {:width 325
                :style "border: 1px solid #888888;"
                :alt "Images disabled? Just come and take a look."
                :src image-link}]]]
       [:p {:style "font-size: 12px"}
        (format "Tell us if this message was sent in error %s." (email-address "info"))
        ;; Add some hidden text so that Google doesn't try to trim these.
        [:span {:style "display: none; max-height: 0px; font-size: 0px; overflow: hidden;"}
         " Sent at "
         (clj-time.format/unparse (clj-time.format/formatters :rfc822) (time/now))
         "."]]]])))

(defn send-access-grant-email [db access-grant-eid]
  (let [access-grant (d/entity db access-grant-eid)
        doc-id (:access-grant/document access-grant)
        granter (access-grant-model/get-granter db access-grant)
        token (:access-grant/token access-grant)
        image-permission (permission-model/create-document-image-permission! {:db/id doc-id})]
    (ses/send-message {:from (email-address "Precursor" "joinme")
                       :to (:access-grant/email access-grant)
                       :subject (str (format-inviter granter)
                                     " invited you to a document on Precursor")
                       :text (str "Hey there,\nCome draw with me on Precursor: " (urls/doc doc-id)
                                  "?access-grant-token=" token)
                       :html (access-grant-html doc-id access-grant image-permission)
                       :o:tracking "yes"
                       :o:tracking-opens "yes"
                       :o:tracking-clicks "no"
                       :o:campaign "access_grant_invites"})))

(defn permission-grant-html [doc-id image-permission]
  (let [doc-link (urls/doc doc-id)
        image-link (urls/doc-png doc-id :query {:rand (rand) :auth-token (:permission/token image-permission)})]
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
                :src image-link}]]]
       [:p {:style "font-size: 12px"}
        (format "Tell us if this message was sent in error %s." (email-address "info"))
        ;; Add some hidden text so that Google doesn't try to trim these.
        [:span {:style "display: none; max-height: 0px; font-size: 0px; overflow: hidden;"}
         " Sent at "
         (clj-time.format/unparse (clj-time.format/formatters :rfc822) (time/now))
         "."]]]])))

(defn send-permission-grant-email [db permission-eid]
  (let [permission (d/entity db permission-eid)
        doc-id (:permission/document permission)
        granter (permission-model/get-granter db permission)
        grantee (d/entity db (:permission/cust permission))
        image-permission (permission-model/create-document-image-permission! {:db/id doc-id})]
    (ses/send-message {:from (email-address "Precursor" "joinme")
                       :to (:cust/email grantee)
                       :subject (str (format-inviter granter)
                                     " gave you access to a document on Precursor")
                       :text (str "Hey there,\nCome draw with me on Precursor: " (urls/doc doc-id))
                       :html (permission-grant-html doc-id image-permission)
                       :o:tracking "yes"
                       :o:tracking-opens "yes"
                       :o:tracking-clicks "no"
                       :o:campaign "access_grant_invites"})))


(defn access-request-html [doc-id requester]
  (let [doc-link (urls/doc doc-id)]
    (hiccup/html
     [:html
      [:body
       [:p (str (format-requester requester) " wants access to one of your documents on Precursor.")]
       [:p "Go to the "
        [:a {:href (urls/doc doc-id :query {:overlay "sharing"})}
         "manage permissions page"]
        " to grant or deny access."]
       [:p {:style "font-size: 12px"}
        (format "Tell us if this message was sent in error %s." (email-address "info"))
        ;; Add some hidden text so that Google doesn't try to trim these.
        [:span {:style "display: none; max-height: 0px; font-size: 0px; overflow: hidden;"}
         " Sent at "
         (clj-time.format/unparse (clj-time.format/formatters :rfc822) (time/now))
         "."]]]])))

(defn send-access-request-email [db request-eid]
  (let [access-request (d/entity db request-eid)
        requester (cust-model/find-by-id db (:access-request/cust access-request))
        doc (doc-model/find-by-id db (:access-request/document access-request))
        doc-id (:db/id doc)
        doc-owner (cust-model/find-by-uuid db (:document/creator doc))]
    (ses/send-message {:from (email-address "Precursor" "joinme")
                       :to (:cust/email doc-owner)
                       :subject (str (format-requester requester)
                                     " wants access to your document on Precursor")
                       :text (str "Hey there,\nSomeone wants access to your document on Precursor: " (urls/doc doc-id)
                                  "\nYou can grant or deny them access from the document's settings page.")
                       :html (access-request-html doc-id requester)
                       :o:tracking "yes"
                       :o:tracking-opens "yes"
                       :o:tracking-clicks "no"
                       :o:campaign "access_request"})))

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


(defn send-early-access-granted-email [db cust-eid]
  (let [cust (cust-model/find-by-id db cust-eid)
        email-addresss (:cust/email cust)]
    (ses/send-message {:from (email-address "Precursor" "early-access")
                       :to (:cust/email cust)
                       :subject "Early access to Precursor"
                       :text (str "You've been granted early access to precursor's paid feaures: https://precursorapp.com")
                       :html (early-access-html cust)})))

(defn send-entity-email-dispatch-fn [db email-enum eid] email-enum)

(defmulti send-entity-email send-entity-email-dispatch-fn)

(defmethod send-entity-email :default
  [db email-enum access-grant-eid]
  (log/infof "No send-entity-email fn for %s" email-enum))

(defmethod send-entity-email :email/access-grant-created
  [db email-enum eid]
  (if (mark-sent-email eid :email/access-grant-created)
    (try+
      (log/infof "sending access-grant email for %s" eid)
      (send-access-grant-email db eid)
      (catch Object t
        (.printStackTrace (:throwable &throw-context))
        (unmark-sent-email eid :email/access-grant-created)
        (throw+ t)))
    (log/infof "not re-sending access-grant email for %s" eid)))

(defmethod send-entity-email :email/access-request-created
  [db email-enum eid]
  (if (mark-sent-email eid :email/access-request-created)
    (try+
      (log/infof "sending access-request email for %s" eid)
      (send-access-request-email db eid)
      (catch Object t
        (.printStackTrace (:throwable &throw-context))
        (unmark-sent-email eid :email/access-request-created)
        (throw+ t)))
    (log/infof "not re-sending access-request email for %s" eid)))

(defmethod send-entity-email :email/document-permission-for-customer-granted
  [db email-enum eid]
  (if (mark-sent-email eid :email/document-permission-for-customer-granted)
    (try+
      (log/infof "sending access-request email for %s" eid)
      (send-permission-grant-email db eid)
      (catch Object t
        (.printStackTrace (:throwable &throw-context))
        (unmark-sent-email eid :email/document-permission-for-customer-granted)
        (throw+ t)))
    (log/infof "not re-sending access-request email for %s" eid)))

(defmethod send-entity-email :email/early-access-granted
  [db email-enum eid]
  (if (mark-sent-email eid :email/early-access-granted)
    (try+
      (log/infof "sending early access email for %s" eid)
      (send-early-access-granted-email db eid)
      (catch Object t
        (.printStackTrace (:throwable &throw-context))
        (unmark-sent-email eid :email/early-access-granted)
        (throw+ t)))
    (log/infof "not re-sending early-access-granted email for %s" eid)))


(defn send-missed-entity-emails-cron
  "Used to catch any emails missed by the transaction watcher."
  []
  (let [db (pcd/default-db)]
    (doseq [[eid email-enum] (d/q '{:find [?t ?email-ident]
                                    :where [[?t :needs-email ?email]
                                            [?email :db/ident ?email-ident]]}
                                  db)]
      (log/infof "queueing %s email for %s" email-enum eid)
      (pc.utils/with-report-exceptions
        (send-entity-email db email-enum eid)))))

(defn init []
  (pc.utils/safe-schedule {:minute (range 0 60 5)} #'send-missed-entity-emails-cron))
