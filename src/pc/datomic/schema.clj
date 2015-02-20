(ns pc.datomic.schema
  (:require [pc.datomic :as pcd]
            [datomic.api :refer [db q] :as d]
            [pc.profile]))

(defn attribute [ident type & {:as opts}]
  (merge {:db/id (d/tempid :db.part/db)
          :db/ident ident
          :db/valueType type
          :db.install/_attribute :db.part/db}
         {:db/cardinality :db.cardinality/one}
         opts))

(defn function [ident quoted-fn & {:as opts}]
  (merge {:db/id (d/tempid :db.part/user)
          :db/ident ident
          :db/fn (d/function quoted-fn)}
         opts))

(defn enum [ident & {:as fields}]
  (merge {:db/id (d/tempid :db.part/user)
          :db/ident ident}
         fields))

;; Attributes that annotate the schema, have to be transacted first
;; Note: metadata needs a migration for it to be removed (or it can be set to false)
(defn metadata-schema []
  [(attribute :metadata/unescaped
              :db.type/boolean
              :db/doc "indicates that an attribute can be changed by a user, so should be escaped")
   ])

(defn schema []
  [

   (attribute :layer/name
              :db.type/string
              :db/doc "Layer name"
              :metadata/unescaped true)

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
              :db.type/string
              :metadata/unescaped true)

   (attribute :layer/stroke-width
              :db.type/float)

   (attribute :layer/stroke-color
              :db.type/string
              :metadata/unescaped true)

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
              :db.type/string
              :metadata/unescaped true)

   (attribute :layer/text
              :db.type/string
              :metadata/unescaped true)

   (attribute :layer/font-size
              :db.type/long)

   (attribute :layer/path
              :db.type/string
              :metadata/unescaped true)

   ;; Wonder what happens if we make a layer a child of one of its children...
   (attribute :layer/child
              :db.type/long
              :db/cardinality :db.cardinality/many
              :db/doc "Layer's children")

   (attribute :layer/ui-id
              :db.type/string
              :db/doc "User-provided identifier for layer"
              :metadata/unescaped true)

   (attribute :layer/ui-target
              :db.type/string
              :db/doc "User-provided action for layer"
              :metadata/unescaped true)

   (attribute :layer/ancestor
              :db.type/long
              :db/doc "db/id of this layer's parent. Could be called \"parent\", but want to avoid confusion with \"child\", which is taken.")

   ;; No logins at the moment, so we'll use this to identify users
   ;; chats rely on this, is it a good idea? Nice to have something stable across tabs
   (attribute :session/uuid
              :db.type/uuid
              :db/index true)

   (attribute :session/client-id
              :db.type/string
              :metadata/unescaped true)

   (attribute :document/uuid
              :db.type/uuid
              :db/index true)

   (attribute :document/name
              :db.type/string
              :metadata/unescaped true)

   (attribute :document/creator
              :db.type/uuid
              :db/index true
              :db/doc "cust/uuid of the cust that created the document")

   (attribute :document/privacy
              :db.type/ref)
   (enum :document.privacy/public)
   (enum :document.privacy/private)

   (attribute :dummy
              :db.type/ref)
   (enum :dummy/dummy)

   (attribute :document/chat-bot
              :db.type/ref)

   (attribute :chat-bot/name
              :db.type/string
              :db/unique :db.unique/identity)

   (attribute :document/id
              :db.type/long
              :db/index true
              :db/doc "Document entity id")

   (attribute :document/invalid-id
              :db.type/long
              :db/index true
              :db/doc "invalid document/id that was migrated")

   (attribute :chat/body
              :db.type/string
              :metadata/unescaped true)

   (attribute :chat/color
              :db.type/string
              :metadata/unescaped true)

   (attribute :server/timestamp
              :db.type/instant)

   (attribute :client/timestamp
              :db.type/instant)

   (attribute :cust/email
              :db.type/string
              :db/index true
              :db/doc "User email"
              :metadata/unescaped true)

   (attribute :cust/name
              :db.type/string
              :db/index false
              :db/doc "User-submitted name"
              :metadata/unescaped true)

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
              :db.type/string
              :metadata/unescaped true)

   (attribute :cust/last-name
              :db.type/string
              :metadata/unescaped true)

   (attribute :cust/birthday
              :db.type/instant)

   (attribute :cust/gender
              :db.type/string
              :metadata/unescaped true)

   (attribute :cust/occupation
              :db.type/string
              :metadata/unescaped true)

   (attribute :browser-setting/tool
              :db.type/ref)
   (enum :circle)
   (enum :rect)
   (enum :line)
   (enum :pen)
   (enum :text)
   (enum :select)

   (attribute :browser-setting/chat-opened
              :db.type/boolean)

   (attribute :browser-setting/chat-mobile-toggled
              :db.type/boolean)

   (attribute :browser-setting/right-click-learned
              :db.type/boolean)

   (attribute :browser-setting/menu-button-learned
              :db.type/boolean)

   (attribute :browser-setting/info-button-learned
              :db.type/boolean)

   (attribute :browser-setting/newdoc-button-learned
              :db.type/boolean)

   (attribute :browser-setting/login-button-learned
              :db.type/boolean)

   (attribute :browser-setting/your-docs-learned
              :db.type/boolean)

   (attribute :browser-setting/main-menu-learned
              :db.type/boolean)

   (attribute :browser-setting/invite-menu-learned
              :db.type/boolean)

   (attribute :browser-setting/sharing-menu-learned
              :db.type/boolean)

   (attribute :browser-setting/shortcuts-menu-learned
              :db.type/boolean)

   (attribute :browser-setting/chat-menu-learned
              :db.type/boolean)

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
   (enum :permission.permits/read)

   (attribute :permission/token
              :db.type/string
              :db/unique :db.unique/value
              :db/doc "secret token used to look up permission")

   (attribute :permission/expiry
              :db.type/instant
              :db/doc "Date permission expires, may be removed from db at this point")

   (attribute :permission/granter
              :db.type/long ;; TODO: make this a ref!
              :db/doc "user that granted the permission")

   (attribute :permission/grant-date
              :db.type/instant
              :db/doc "time permission was created (or access-grant if it came first)")

   (attribute :permission/doc-cust
              :db.type/uuid
              :db/unique :db.unique/identity
              :db/doc "Used to add a composite uniqueness constraint on doc and cust.")

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

   (attribute :access-request/create-date
              :db.type/instant
              :db/doc "date request was created")

   ;; TODO: need a way to let the frontend perform history queries
   (attribute :access-request/deny-date
              :db.type/instant
              :db/doc "date request was denied")

   (attribute :access-request/doc-cust
              :db.type/uuid
              :db/unique :db.unique/identity
              :db/doc "Used to add a composite uniqueness constraint on doc and cust.")

   ;; used when access is granted to someone without an account
   (attribute :access-grant/document
              :db.type/long
              :db/index true
              :db/doc "db/id of the document")

   (attribute :access-grant/email
              :db.type/string
              :db/doc "email that was granted access"
              :metadata/unescaped true)

   (attribute :access-grant/token
              :db.type/string
              :db/doc "correlates email that was granted access with the account that claims the grant")

   (attribute :access-grant/expiry
              :db.type/instant
              :db/doc "time that the access-grant expires")

   (attribute :access-grant/granter
              :db.type/long ;; TODO: make this a ref!
              :db/doc "user that granted the permission")

   (attribute :access-grant/grant-date
              :db.type/instant
              :db/doc "time that the access-grant was created")

   (attribute :access-grant/doc-email
              :db.type/string
              :db/unique :db.unique/identity
              :db/doc "Used to add a composite uniqueness constraint on doc and email.")

   (attribute :transaction/broadcast
              :db.type/boolean
              :db/doc "Used to annotate transaction and let frontend know if it should broadcast")
   (attribute :transaction/source
              :db.type/ref
              :db/doc "Annotates transaction")
   (enum :transaction.source/unmark-sent-email)
   (enum :transaction.source/mark-sent-email)
   (enum :transaction.source/migration)

   (attribute :migration
              :db.type/ref
              :db/doc "Annotates transaction with migration")
   (enum :migration/add-frontend-ids)

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
   (enum :email/document-permission-for-customer-granted)
   (enum :email/fake)

   (attribute :flags
              :db.type/ref
              :db/cardinality :db.cardinality/many
              :db/doc "Annotate an entity with feature flags")
   (enum :flags/private-docs)

   (attribute :pre-made
              :db.type/ref
              :db/doc "dummy attribute so that we can pre-generate entity ids for a migration")
   (enum :pre-made/free-to-postgres)

   (attribute :frontend/id
              :db.type/uuid
              :db/unique :db.unique/identity
              :db/doc (str "UUID whose least significant bits can be created on the frontend. "
                           "Most significant bits are a namespace, like the document id."))

   (function :pc.datomic.web-peer/assign-frontend-id
             '{:lang :clojure
               :params [db entity-id namespace-part multiple remainder]
               :code (let [used-ids (map #(.getLeastSignificantBits (:v %))
                                         (d/index-range db
                                                        :frontend/id
                                                        (java.util.UUID. namespace-part 0)
                                                        (java.util.UUID. namespace-part Long/MAX_VALUE)))
                           used-from-partition (set (filter #(= remainder (mod % multiple)) used-ids))
                           client-part (first (remove #(contains? used-from-partition %)
                                                      (iterate (partial + multiple) (if (zero? remainder)
                                                                                      multiple
                                                                                      remainder))))]
                       [[:db/add entity-id :frontend/id (java.util.UUID. namespace-part client-part)]])}
             :db/doc "Assigns frontend-id, meant to be used with the partition reserved for the backend")])

(defonce schema-ents (atom nil))

(defn enums []
  (set (map :db/ident (filter #(= :db.type/ref (:db/valueType %))
                              @schema-ents))))

(defn get-ident [a]
  (:db/ident (first (filter #(= a (:db/id %)) @schema-ents))))

(defn metadata [a]
  (let [ent (first (filter #(or (= a (:db/id %))
                                (= a (:db/ident %))) @schema-ents))]
    (select-keys ent (filter #(= "metadata" (namespace %)) (keys ent)))))

(defn unescaped? [a]
  (:metadata/unescaped (metadata a)))

(defn get-schema-ents [db]
  (pcd/touch-all '{:find [?t]
                   :where [[?t :db/ident ?ident]]}
                 db))

(defn browser-setting-idents []
  (reduce (fn [acc ent]
            (if (= "browser-setting" (namespace (:db/ident ent)))
              (conj acc (:db/ident ent))
              acc))
          [] @schema-ents))

(defn ensure-schema
  ([] (ensure-schema (pcd/conn)))
  ([conn]
   (let [meta-res @(d/transact conn (metadata-schema))
         res @(d/transact conn (schema))
         ents (get-schema-ents (:db-after res))]
     (reset! schema-ents ents)
     res)))

(defn init []
  (ensure-schema))
