(ns pc.email
  (:require [clj-time.core :as time]
            [clj-time.format]
            [clojure.java.io :as io]
            [clojure.pprint]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [me.raynes.fs :as fs]
            [hiccup.core :as hiccup]
            [pc.datomic :as pcd]
            [pc.datomic.web-peer :as web-peer]
            [pc.http.urls :as urls]
            [pc.models.access-grant :as access-grant-model]
            [pc.models.cust :as cust-model]
            [pc.models.doc :as doc-model]
            [pc.models.permission :as permission-model]
            [pc.models.team :as team-model]
            [pc.profile :as profile]
            [pc.ses :as ses]
            [pc.utils]
            [pc.views.invoice :as invoice-view]
            [pc.views.email :as view]
            [slingshot.slingshot :refer (throw+ try+)]))

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



(defn send-chat-invite [{:keys [cust to-email doc]}]
  (ses/send-message {:from (view/email-address "Precursor" "joinme")
                     :to to-email
                     :subject (str (view/format-inviter cust)
                                   " invited you to a document on Precursor")
                     :text (str "Hey there,\nMake something with me on Precursor. " (urls/from-doc doc))
                     :html (view/chat-invite-html doc)}))

(defn send-document-access-grant-email [db access-grant]
  (let [doc (:access-grant/document-ref access-grant)
        granter (:access-grant/granter-ref access-grant)
        token (:access-grant/token access-grant)
        image-permission (permission-model/create-document-image-permission! doc)]
    (ses/send-message {:from (view/email-address "Precursor" "joinme")
                       :to (:access-grant/email access-grant)
                       :subject (str (view/format-inviter granter)
                                     " invited you to a document on Precursor")
                       :text (str "Hey there,\nMake something with me on Precursor. " (urls/from-doc doc)
                                  "?access-grant-token=" token)
                       :html (view/document-access-grant-html doc access-grant image-permission)})))

(defn send-team-access-grant-email [db access-grant]
  (let [team (:access-grant/team access-grant)
        subdomain (:team/subdomain team)
        granter (:access-grant/granter-ref access-grant)
        token (:access-grant/token access-grant)]
    (ses/send-message {:from (view/email-address "Precursor" "joinme")
                       :to (:access-grant/email access-grant)
                       :subject (str (view/format-inviter granter)
                                     " invited you to join the "
                                     subdomain " team on Precursor")
                       :text (str "Hey there,\nYou've been invited to the " subdomain " team Precursor: "
                                  (urls/root :query {:access-grant-token token}
                                             :subdomain subdomain))
                       :html (view/team-access-grant-html team access-grant)})))

(defn send-access-grant-email [db access-grant-eid]
  (let [access-grant (d/entity db access-grant-eid)]
    (cond (:access-grant/document-ref access-grant)
          (send-document-access-grant-email db access-grant)

          (:access-grant/team access-grant)
          (send-team-access-grant-email db access-grant)

          :else
          (throw+ {:error :missing-email-handler-for-access-grant
                   :access-grant access-grant}))))

(defn send-document-permission-grant-email [db permission]
  (let [doc (:permission/document-ref permission)
        granter (:permission/granter-ref permission)
        grantee (:permission/cust-ref permission)
        image-permission (permission-model/create-document-image-permission! doc)]
    (ses/send-message {:from (view/email-address "Precursor" "joinme")
                       :to (:cust/email grantee)
                       :subject (str (view/format-inviter granter)
                                     " invited you to a document on Precursor")
                       :text (str "Hey there,\nMake something with me on Precursor. " (urls/from-doc doc))
                       :html (view/document-permission-grant-html doc image-permission)})))

(defn send-team-permission-grant-email [db permission]
  (let [team (:permission/team permission)
        subdomain (:team/subdomain team)
        granter (:permission/granter-ref permission)
        grantee (:permission/cust-ref permission)]
    (ses/send-message {:from (view/email-address "Precursor" "joinme")
                       :to (:cust/email grantee)
                       :subject (str (view/format-inviter granter)
                                     " invited you to the "
                                     subdomain " team on Precursor")
                       :text (str "Hey there,\nYou've been invited to the " subdomain " team Precursor: "
                                  urls/root :subdomain subdomain)
                       :html (view/team-permission-grant-html team)})))

(defn send-permission-grant-email [db permission-eid]
  (let [permission (d/entity db permission-eid)]
    (cond (:permission/document-ref permission)
          (send-document-permission-grant-email db permission)

          (:permission/team permission)
          (send-team-permission-grant-email db permission)

          :else
          (throw+ {:error :missing-email-handler-for-permission
                   :permission permission}))))

(defn send-document-access-request-email [db access-request]
  (let [requester (:access-request/cust-ref access-request)
        doc (:access-request/document-ref access-request)
        doc-owner (cond (:document/creator doc)
                        (cust-model/find-by-uuid db (:document/creator doc))

                        (:document/team doc)
                        (->> (:document/team doc)
                          (permission-model/find-by-team db)
                          (filter :permission/cust-ref)
                          (sort-by :permission/grant-date)
                          first
                          :permission/cust-ref)

                        :else (throw+ {:error :no-doc-owner :msg (format "Nobody owns the doc %s" (:db/id doc))}))]
    (ses/send-message {:from (view/email-address "Precursor" "joinme")
                       :to (:cust/email doc-owner)
                       :subject (str (view/format-requester requester)
                                     " wants access to your document on Precursor")
                       :text (str "Hey there,\nSomeone wants access to your document on Precursor: " (urls/from-doc doc)
                                  "\nYou can grant or deny them access from the document's settings page.")
                       :html (view/document-access-request-html doc requester)})))

(defn send-team-access-request-email [db access-request]
  (let [requester (:access-request/cust-ref access-request)
        team (:access-request/team access-request)
        subdomain (:team/subdomain team)
        team-owner (->> team
                     (permission-model/find-by-team db)
                     (filter :permission/cust-ref)
                     (sort-by :permission/grant-date)
                     first
                     :permission/cust-ref)]
    (ses/send-message {:from (view/email-address "Precursor" "joinme")
                       :to (:cust/email team-owner)
                       :subject (str (view/format-requester requester)
                                     " wants to join the "
                                     subdomain " team on Precursor")
                       :text (str "Hey there,\nSomeone wants access to the " subdomain " team on Precursor" (urls/root :subdomain subdomain)
                                  "\nYou can grant or deny them access from the document's settings page.")
                       :html (view/team-access-request-html team requester)})))

(defn send-access-request-email [db access-request-eid]
  (let [access-request (d/entity db access-request-eid)]
    (cond (:access-request/document-ref access-request)
          (send-document-access-request-email db access-request)

          (:access-request/team access-request)
          (send-team-access-request-email db access-request)

          :else
          (throw+ {:error :missing-email-handler-for-access-request
                   :access-request access-request}))))

(defn send-early-access-granted-email [db cust-eid]
  (let [cust (cust-model/find-by-id db cust-eid)
        email-addresss (:cust/email cust)]
    (ses/send-message {:from (view/email-address "Precursor" "early-access")
                       :to (:cust/email cust)
                       :subject "Early access to Precursor"
                       :text (str "You've been granted early access to precursor's paid feaures: https://precursorapp.com")
                       :html (view/early-access-html cust)})))

(defn send-invoice-created-email [db invoice-id]
  (let [invoice (d/entity db invoice-id)
        team (team-model/find-by-invoice db invoice)]
    (ses/send-message {:from (view/email-address "Precursor" "billing")
                       :to (:plan/billing-email (:team/plan team))
                       :subject "Precursor Invoice"
                       :text (str "You have a new invoice for the " (:team/subdomain team) " team.")
                       :html (view/invoice-html team invoice)
                       :attachments (let [temp (fs/temp-file)]
                                      ;; only supports files :(
                                      (invoice-view/render-pdf team invoice temp)
                                      [{:content temp
                                        :content-type "application/pdf"
                                        :file-name (str "Precursor invoice #" (web-peer/client-id invoice))}])})))

(defmacro with-email-accounting [email-enum eid & body]
  `(if (mark-sent-email ~eid ~email-enum)
     (try+
      (log/infof "sending %s email for %s" (name ~email-enum) ~eid)
      ~@body
      (catch Object t#
        (log/errorf (:throwable ~'&throw-context) "error sending %s email for %s" (name ~email-enum) ~eid)
        (.printStackTrace (:throwable ~'&throw-context))
        (unmark-sent-email ~eid ~email-enum)
        (throw+ t#)))
     (log/infof "not re-sending %s email for %s" (name ~email-enum) ~eid)))

(defn send-entity-email-dispatch-fn [db email-enum eid] email-enum)

(defmulti send-entity-email send-entity-email-dispatch-fn)

(defmethod send-entity-email :default
  [db email-enum access-grant-eid]
  (log/infof "No send-entity-email fn for %s" email-enum))

(defmethod send-entity-email :email/access-grant-created
  [db email-enum eid]
  (with-email-accounting email-enum eid
    (send-access-grant-email db eid)))

(defmethod send-entity-email :email/access-request-created
  [db email-enum eid]
  (with-email-accounting email-enum eid
    (send-access-request-email db eid)))

(defmethod send-entity-email :email/permission-granted
  [db email-enum eid]
  (with-email-accounting email-enum eid
    (send-permission-grant-email db eid)))

(defmethod send-entity-email :email/early-access-granted
  [db email-enum eid]
  (with-email-accounting email-enum eid
    (send-early-access-granted-email db eid)))

(defmethod send-entity-email :email/invoice-created
  [db email-enum eid]
  (with-email-accounting email-enum eid
    (send-invoice-created-email db eid)))

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

(defn send-connection-stats [data]
  (ses/send-message {:from (view/email-address "Precursor" "connection-stats")
                     :to "dev@precursorapp.com"
                     :subject (str "Connection stats for " (:client-id data))
                     :html (hiccup/html
                            [:pre (with-out-str (clojure.pprint/pprint data))])}))

(defn init []
  (pc.utils/safe-schedule {:minute (range 0 60 5)} #'send-missed-entity-emails-cron))
