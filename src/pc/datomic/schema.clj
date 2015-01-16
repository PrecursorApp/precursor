(ns pc.datomic.schema
  (:require [pc.datomic :as pcd]
            [datomic.api :refer [db q] :as d]))

(defn attribute [ident type & {:as opts}]
  (merge {:db/id (d/tempid :db.part/db)
          :db/ident ident
          :db/valueType type
          :db.install/_attribute :db.part/db}
         {:db/cardinality :db.cardinality/one}
         opts))

(defn function [ident fn & {:as opts}]
  (merge {:db/id (d/tempid :db.part/user)
          :db/ident ident
          :db/fn fn}
         opts))

(defn enum [ident]
  {:db/id (d/tempid :db.part/user)
   :db/ident ident})

(def schema
  [
   (attribute :layer/name
              :db.type/string
              :db/doc "Layer name")

   (attribute :layer/uuid
              :db.type/uuid
              :db/index true
              :db/doc "Uuid set on the frontend when it creates the layer")

   (attribute :layer/type
              :db.type/ref
              :db/doc "Layer type")
   ;; TODO: more layer types
   (enum :layer.type/rect)
   (enum :layer.type/group)
   (enum :layer.type/text)
   (enum :layer.type/line)
   (enum :layer.type/path)

   (attribute :layer/start-x
              :db.type/float)

   (attribute :layer/start-y
              :db.type/float)

   (attribute :layer/end-x
              :db.type/float)

   (attribute :layer/end-y
              :db.type/float)

   (attribute :layer/rx
              :db.type/float
              :db/doc "Border radius")

   (attribute :layer/ry
              :db.type/float
              :db/doc "Border radius")

   (attribute :layer/fill
              :db.type/string)

   (attribute :layer/stroke-width
              :db.type/float)

   (attribute :layer/stroke-color
              :db.type/string)

   (attribute :layer/opacity
              :db.type/float)

   (attribute :entity/type
              :db.type/ref)
   (enum :layer)

   (attribute :layer/start-sx
              :db.type/float)

   (attribute :layer/start-sy
              :db.type/float)

   (attribute :layer/current-sx
              :db.type/float)

   (attribute :layer/current-sy
              :db.type/float)

   (attribute :layer/font-family
              :db.type/string)

   (attribute :layer/text
              :db.type/string)

   (attribute :layer/font-size
              :db.type/long)

   (attribute :layer/path
              :db.type/string)

   ;; Wonder what happens if we make a layer a child of one of its children...
   (attribute :layer/child
              :db.type/long
              :db/cardinality :db.cardinality/many
              :db/doc "Layer's children")

   (attribute :layer/ui-id
              :db.type/string
              :db/doc "User-provided identifier for layer")

   (attribute :layer/ui-target
              :db.type/string
              :db/doc "User-provided action for layer")

   (attribute :layer/ancestor
              :db.type/long
              :db/doc "db/id of this layer's parent. Could be called \"parent\", but want to avoid confusion with \"child\", which is taken.")

   ;; No logins at the moment, so we'll use this to identify users
   ;; chats rely on this, is it a good idea? Nice to have something stable across tabs
   (attribute :session/uuid
              :db.type/uuid
              :db/index true)

   (attribute :session/client-id
              :db.type/string)

   (attribute :document/uuid
              :db.type/uuid
              :db/index true)

   (attribute :document/name
              :db.type/string)

   (attribute :document/creator
              :db.type/uuid
              :db/index true
              :db/doc "cust/uuid of the cust that created the document")

   (attribute :document/privacy
              :db.type/ref)
   (enum :document.privacy/public)
   (enum :document.privacy/private)

   (attribute :document.collaborators
              :db.type/uuid
              :db/doc "cust/uuid of any users that were invited")

   (attribute :dummy
              :db.type/ref)
   (enum :dummy/dummy)

   (attribute :document/id
              :db.type/long
              :db/index true
              :db/doc "Document entity id")

   (attribute :document/invalid-id
              :db.type/long
              :db/index true
              :db/doc "invalid document/id that was migrated")

   (attribute :chat/body
              :db.type/string)

   (attribute :chat/color
              :db.type/string)

   (attribute :server/timestamp
              :db.type/instant)

   (attribute :client/timestamp
              :db.type/instant)

   (attribute :cust/email
              :db.type/string
              :db/index true
              :db/doc "User email")

   (attribute :cust/name
              :db.type/string
              :db/index false
              :db/doc "User-submitted name")

   (attribute :cust/verified-email
              :db.type/boolean)

   (attribute :cust/guessed-dribbble-username
              :db.type/string)

   (attribute :google-account/sub
              :db.type/string
              :db/unique :db.unique/value
              :db/doc "Account id unique across Google: https://developers.google.com/accounts/docs/OAuth2Login")

   (attribute :google-account/avatar
              :db.type/uri)

   (attribute :cust/uuid
              :db.type/uuid
              :db/index true)

   (attribute :cust/http-session-key
              :db.type/uuid
              :db/index true
              :db/doc "Session key stored in the cookie that is used to find the user")

   (attribute :cust/first-name
              :db.type/string)

   (attribute :cust/last-name
              :db.type/string)

   (attribute :cust/birthday
              :db.type/instant)

   (attribute :cust/gender
              :db.type/string)

   (attribute :cust/occupation
              :db.type/string)

   (attribute :permission/document
              :db.type/long
              :db/index true
              :db/doc "db/id of the document")

   (attribute :permission/cust
              :db.type/long
              :db/doc "db/id of the user")

   (attribute :permission/permits
              :db.type/ref
              :db/cardinality :db.cardinality/many
              :db/doc "permission granted by permission")

   (enum :permission.permits/admin)


   (function :pc.models.permission/grant-permit
             #db/fn {:lang :clojure
                     :params [db doc-id cust-id permit]
                     :code (if-let [id (ffirst (d/q '{:find [?t]
                                                      :in [$ ?doc-id ?cust-id]
                                                      :where [[?t :permission/document ?doc-id]
                                                              [?t :permission/cust ?cust-id]]}
                                                    db doc-id cust-id))]
                             [[:db/add id :permission/permits permit]]
                             (let [temp-id (d/tempid :db.part/user)]
                               [[:db/add temp-id :permission/document doc-id]
                                [:db/add temp-id :permission/cust cust-id]
                                [:db/add temp-id :permission/permits permit]]))}
             :db/doc "Adds a permit, with composite uniqueness constraint on doc and cust")

   (attribute :access-request/document
              :db.type/long
              :db/index true
              :db/doc "db/id of the document")
   (attribute :access-request/status
              :db.type/ref)
   ;; no granted status, b/c those are just permissions
   (enum :access-request.status/pending)
   (enum :access-request.status/denied)

   (attribute :access-request/cust
              :db.type/long
              :db/doc "db/id of the user")

   (function :pc.models.access-request/create-request
             #db/fn {:lang :clojure
                     :params [db doc-id cust-id & extra-fields]
                     :code (when-not (ffirst (d/q '{:find [?t]
                                                    :in [$ ?doc-id ?cust-id]
                                                    :where [[?t :access-request/document ?doc-id]
                                                            [?t :access-request/cust ?cust-id]]}
                                                  db doc-id cust-id))
                             (let [temp-id (d/tempid :db.part/user)]
                               (concat [[:db/add temp-id :access-request/document doc-id]
                                        [:db/add temp-id :access-request/cust cust-id]
                                        [:db/add temp-id :access-request/status :access-request.status/pending]]
                                       (for [[field value] extra-fields]
                                         [:db/add temp-id field value]))))}
             :db/doc "Adds an access request, with composite uniqueness constraint on doc and cust")

   ;; used when access is granted to someone without an account
   (attribute :access-grant/document
              :db.type/long
              :db/index true
              :db/doc "db/id of the document")

   (attribute :access-grant/email
              :db.type/string
              :db/doc "email that was granted access")

   (attribute :access-grant/token
              :db.type/string
              :db/doc "correlates email that was granted access with the account that claims the grant")

   (attribute :access-grant/expiry
              :db.type/instant
              :db/doc "time that the access-grant expires")

   (attribute :access-grant/granter
              :db.type/long ;; TODO: make this a ref!
              :db/doc "time that the access-grant expires")

   (function :pc.models.access-grant/create-grant
             #db/fn {:lang :clojure
                     :params [db doc-id granter-id email token expiry & extra-fields]
                     :code (when-not (ffirst (d/q '{:find [?t]
                                                    :in [$ ?doc-id ?email ?now]
                                                    :where [[?t :access-grant/document ?doc-id]
                                                            [?t :access-grant/email ?email]
                                                            [?t :access-grant/expiry ?expiry]
                                                            [(> ?expiry ?now)]]}
                                                  db doc-id email (java.util.Date.)))
                             (let [temp-id (d/tempid :db.part/user)]
                               (concat [[:db/add temp-id :access-grant/document doc-id]
                                        [:db/add temp-id :access-grant/email email]
                                        [:db/add temp-id :access-grant/token token]
                                        [:db/add temp-id :access-grant/expiry expiry]
                                        [:db/add temp-id :access-grant/granter granter-id]]
                                       (for [[field value] extra-fields]
                                         [:db/add temp-id field value]))))}
             :db/doc "Adds a grant, with composite uniqueness constraint on doc and email, accounting for expiration")

   (attribute :transaction/broadcast
              :db.type/boolean
              :db/doc "Used to annotate transaction and let frontend know if it should broadcast")
   (attribute :transaction/source
              :db.type/ref
              :db/doc "Annotates transaction")
   (enum :transaction.source/unmark-sent-email)
   (enum :transaction.source/mark-sent-email)

   ;; TODO: this may be a bad idea, revisit if it doesn't work well in practice
   (attribute :needs-email
              :db.type/ref
              :db/index true
              :db/cardinality :db.cardinality/many
              :db/doc "Annotate an entity to say that it needs an email")

   (attribute :sent-email
              :db.type/ref
              :db/cardinality :db.cardinality/many
              :db/doc "Annotate an entity to say that a given email has been sent")

   (enum :email/access-grant-created)
   (enum :email/access-request-created)
   (enum :email/fake)

   (attribute :flags
              :db.type/ref
              :db/cardinality :db.cardinality/many
              :db/doc "Annotate an entity with feature flags")
   (enum :flags/private-docs)])

(defonce schema-ents (atom nil))

(defn enums []
  (set (map :db/ident (filter #(= :db.type/ref (:db/valueType %))
                              @schema-ents))))

(defn get-ident [a]
  (:db/ident (first (filter #(= a (:db/id %)) @schema-ents))))

(defn get-schema-ents [db]
  (pcd/touch-all '{:find [?t]
                   :where [[?t :db/ident ?ident]]}
                 db))

(defn ensure-schema
  ([] (ensure-schema (pcd/conn)))
  ([conn]
   (let [res @(d/transact conn schema)
         ents (get-schema-ents (:db-after res))]
     (reset! schema-ents ents)
     res)))

(defn init []
  (ensure-schema))
