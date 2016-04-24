(ns pc.profile
  "Keeps track of env-specific settings"
  (:require [clj-pgp.message :as pgp]
            [clojure.tools.reader.edn :as edn]
            [clojure.java.io :as io]
            [schema.core :as s]))

(defn prod? []
  (= "true" (System/getenv "PRODUCTION")))

(defn env []
  (if (prod?)
    "production"
    "development"))

(def Admin {:admin/email s/Str
            :google-account/sub s/Str})

(def SessionKey (s/constrained s/Str
                               (fn sixteen-chars [k] (= (count k) 16))))

(def ProfileSchema
  {:force-ssl? s/Bool
   :compile-less? s/Bool
   :prod-assets? s/Bool
   :log-to-console? s/Bool
   :use-email-whitelist? s/Bool
   :bcc-audit-log? s/Bool
   :allow-mismatched-servername? s/Bool
   :register-twilio-callbacks? s/Bool
   :fetch-stripe-events? s/Bool
   :send-librato-events? s/Bool

   :http-port s/Num
   :https-port s/Num
   :admin-http-port s/Num
   :admin-https-port s/Num

   :prod-domain s/Str
   :hostname s/Str
   :admin-hostname s/Str
   :http-session-key SessionKey
   :admin-http-session-key SessionKey

   :datomic-uri s/Str
   :admin-datomic-uri s/Str

   :statsd-host s/Str
   :memcached-server s/Str

   :slack-customer-ping-url s/Str

   :s3-region s/Str

   :clipboard-bucket s/Str
   :clipboard-s3-access-key s/Str
   :clipboard-s3-secret-key s/Str

   :doc-image-bucket s/Str
   :doc-image-s3-access-key s/Str
   :doc-image-s3-secret-key s/Str

   :deploy-aws-access-key s/Str
   :deploy-aws-secret-key s/Str

   :deploy-s3-bucket s/Str

   :cdn-bucket s/Str
   :cdn-aws-access-key s/Str
   :cdn-aws-secret-key s/Str

   :cdn-distribution-id s/Str
   :cdn-base-url s/Str

   :ses-access-key-id s/Str
   :ses-secret-access-key s/Str

   :ses-smtp-user s/Str
   :ses-smtp-pass s/Str
   :ses-smtp-host s/Str

   :mailgun-base-url s/Str
   :mailgun-api-key s/Str

   :mailchimp-list-id s/Str
   :mailchimp-api-key s/Str

   :stripe-secret-key s/Str
   :stripe-publishable-key s/Str

   :librato-api-name s/Str
   :librato-api-token s/Str

   :google-api-key s/Str
   :google-client-id s/Str
   :google-client-secret s/Str
   :gcs-access-id s/Str

   :admin-google-client-id s/Str
   :admin-google-client-secret s/Str

   :google-analytics-token s/Str

   :stack-exchange-api-key s/Str

   :twilio-phone-number s/Str
   :twilio-auth-token s/Str
   :twilio-sid s/Str

   :admins [Admin]

   :mixpanel-api-key s/Str
   :mixpanel-api-token s/Str
   :mixpanel-api-secret s/Str

   :prcrsr-bot-email s/Str

   :dribbble-custom-search-id s/Str
   :product-hunt-api-token s/Str

   :rollbar-token s/Str
   :rollbar-client-token s/Str})

(defn dev-defaults []
  {:force-ssl? false
   :compile-less? true
   :prod-assets? false
   :log-to-console? true
   :use-email-whitelist? true
   :bcc-audit-log? false
   :allow-mismatched-servername? true
   :register-twilio-callbacks? false
   :fetch-stripe-events false
   :send-librato-events? false

   :http-port 8080
   :https-port 8078
   :admin-http-port 9080
   :admin-https-port 9078

   :prod-domain "precursorapp.com"
   :hostname "localhost"
   :admin-hostname "localhost"

   :datomic-uri "datomic:dev://localhost:4334/prcrsr"
   :admin-datomic-uri "datomic:dev://localhost:4334/prcrsr-admin"

   :statsd-host "10.99.0.104"

   :s3-region "us-west-2"})

(defn profile-source
  "Returns path to encrypted profile edn file. Should match ProfileSchema."
  []
  (or (System/getenv "PROFILE_SOURCE")
      (if (prod?)
        "secrets/production-profile.edn.gpg"
        "secrets/development-profile.edn.gpg")))

(defonce secrets-atom (atom {}))

(defn get-secret [k]
  (get @secrets-atom k (when (not (prod?)) (get dev-defaults k))))

(defn safe-validate
  "Validate schema without logging values"
  [schema value]
  (try
    (s/validate schema value)
    (catch clojure.lang.ExceptionInfo e
      (throw (ex-info (.getMessage e) (dissoc (ex-data e) :value))))))

(defn gpg-passphrase []
  (System/getenv "GPG_PASSPHRASE"))

(defn interactively-read-passphrase [msg]
  (assert (not (prod?)) "Can't run interactive code in prod")
  (print msg)
  (flush)
  (read-line))

(defn read-secrets [passphrase resource]
  (-> resource
      slurp
      (pgp/decrypt passphrase)
      edn/read-string
      (#(safe-validate ProfileSchema %))))

(defn load-secrets []
  (assert (gpg-passphrase) "Must export GPG_PASSPHRASE")
  (->> (io/resource (profile-source))
       (read-secrets (gpg-passphrase))
       (reset! secrets-atom)))

(defn encrypt-secrets [passphrase secrets]
  (pgp/encrypt (pr-str secrets)
               passphrase
               :format :utf8
               :cipher :aes-256
               :armor true))

(defn write-secrets! [secrets file]
  (safe-validate ProfileSchema secrets)
  (let [passphrase (interactively-read-passphrase (format "Enter gpg passphrase for %s" file))]
    (spit file (encrypt-secrets passphrase secrets))))

;; a bit hacky, but it lets us call `pc.profile/compile-less?`, which
;; helps prevent typos, is easier to refactor, and is more concise
;; Downside is that jump-to-definition doesn't work :/.
;; A `get-config` macro that checks for the value in the schema at compile-time
;; may have been a better choice.
(doseq [k (keys ProfileSchema)]
  (intern *ns* (symbol (name k)) (fn [] (get-secret k))))

(defn init []
  (load-secrets))
