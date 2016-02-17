(ns pc.profile
  "Keeps track of env-specific settings"
  (:require [clojure.tools.reader.edn :as edn]))

(defn get-assert-env [env-str]
  (if-let [val (System/getenv env-str)]
    val
    (throw (java.lang.AssertionError. (format "please export %s" env-str)))))

(defn prod? []
  (= "true" (System/getenv "PRODUCTION")))

(defn compile-less? []
  (not (prod?)))

(defn prod-assets? []
  (prod?))

(defn log-to-console? []
  (not (prod?)))

(defn http-port []
  (if (System/getenv "HTTP_PORT")
    (Integer/parseInt (System/getenv "HTTP_PORT"))
    8080))

(defn admin-http-port []
  (if (System/getenv "ADMIN_HTTP_PORT")
    (Integer/parseInt (System/getenv "ADMIN_HTTP_PORT"))
    9080))

(defn https-port []
  (if (System/getenv "HTTPS_PORT")
    (Integer/parseInt (System/getenv "HTTPS_PORT"))
    (if (prod?) 443 8078)))

(defn admin-https-port []
  (if (System/getenv "ADMIN_HTTPS_PORT")
    (Integer/parseInt (System/getenv "ADMIN_HTTPS_PORT"))
    (if (prod?) 443 9078)))

(defn force-ssl? []
  (prod?))

(defn prod-domain []
  "precursorapp.com")

(defn hostname []
  (if (prod?)
    (prod-domain)
    "localhost"))

(defn admin-hostname []
  (if (prod?)
    (str "admin." (prod-domain))
    "localhost"))

;; 16-characer random string
(defn http-session-key []
  (get-assert-env "HTTP_SESSION_KEY"))

(defn admin-http-session-key []
  (get-assert-env "ADMIN_HTTP_SESSION_KEY"))

(defn env []
  (if (prod?)
    "production"
    "development"))

(defn datomic-uri []
  (or
   (System/getenv "DATOMIC_URI")
   (if (prod?)
     "datomic:ddb://us-west-2/prcrsr-datomic/prcrsr"
     "datomic:free://localhost:4334/pc2")))

(defn admin-datomic-uri []
  (or (System/getenv "ADMIN_DATOMIC_URI")
      (if (pc.profile/prod?)
        "datomic:ddb://us-west-2/prcrsr-datomic/prcrsr-admin"
        "datomic:free://localhost:4334/prcrsr-admin")))

(defn statsd-host []
  ;; goes to localhost if it can't resolve
  "10.99.0.104")

(defn use-email-whitelist?
  "Used to guard against sending emails to customers from dev-mode"
  []
  (or (System/getenv "USE_EMAIL_WHITELIST")
      (not (prod?))))

(defn bcc-audit-log?
  "Determines whether to bcc audit-log@ on every email"
  []
  (prod?))

(defn allow-mismatched-servername? []
  (not (prod?)))

(defn register-twilio-callbacks? []
  (prod?))

(defn memcached-server []
  (System/getenv "MEMCACHED_SERVER"))

(defn fetch-stripe-events? []
  (not (prod?)))

(defn slack-customer-ping-url []
  (System/getenv "SLACK_CUSTOMER_PING_URL"))

(defn s3-region []
  "us-west-2")

(defn clipboard-bucket []
  (if (prod?)
    "prcrsr-clipboard"
    "prcrsr-clipboard-dev"))

(defn clipboard-s3-access-key []
  (System/getenv "CLIPBOARD_S3_ACCESS_KEY"))

(defn clipboard-s3-secret-key []
  (System/getenv "CLIPBOARD_S3_SECRET_KEY"))

(defn doc-image-bucket []
  (if (prod?)
    "prcrsr-doc-images"
    "prcrsr-doc-images-dev"))

(defn doc-image-s3-access-key []
  (System/getenv "DOC_IMAGES_S3_ACCESS_KEY"))

(defn doc-image-s3-secret-key []
  (System/getenv "DOC_IMAGES_S3_SECRET_KEY"))

(defn deploy-aws-access-key []
  (System/getenv "DEPLOY_AWS_ACCESS_KEY"))

(defn deploy-aws-secret-key []
  (System/getenv "DEPLOY_AWS_SECRET_KEY"))

(defn deploy-s3-bucket []
  "prcrsr-deploys")

(defn send-librato-events? []
  (prod?))

(defn cdn-bucket []
  "prcrsr-cdn")

;; XXX: rotate!
(defn cdn-aws-access-key []
  (System/getenv "CDN_AWS_ACCESS_KEY"))

;; XXX: rotate!
(defn cdn-aws-secret-key []
  (System/getenv "CDN_AWS_SECRET_KEY"))

(defn cdn-distribution-id []
  (System/getenv "CDN_DISTRIBUTION_ID"))

(defn cdn-base-url [] "https://dtwdl3ecuoduc.cloudfront.net")

(defn ses-access-key-id []
  (System/getenv "SES_ACCESS_KEY_ID"))

(defn ses-secret-access-key []
  (System/getenv "SES_SECRET_ACCESS_KEY"))
(defn ses-smtp-user []
  (System/getenv "SES_SMTP_USER"))
(defn ses-smtp-pass []
  (System/getenv "SES_SMTP_PASS"))
(defn ses-smtp-host []
  "email-smtp.us-west-2.amazonaws.com")

(defn google-client-secret []
  (System/getenv "GOOGLE_CLIENT_SECRET"))
(defn google-client-id []
  (System/getenv "GOOGLE_CLIENT_ID"))

(defn admin-google-client-secret []
  (System/getenv "ADMIN_GOOGLE_CLIENT_SECRET"))
(defn admin-google-client-id []
  (System/getenv "ADMIN_GOOGLE_CLIENT_ID"))

(defn google-api-key []
  (System/getenv "GOOGLE_API_KEY"))

(defn dribbble-custom-search-id []
  (System/getenv "DRIBBLE_CUSTOM_SEARCH_ID"))

(defn gcs-access-id []
  (System/getenv "GCS_ACCESS_ID"))

(defn google-analytics-token []
  "UA-56552392-1")

;; Should be a pr-str'd collection of admin maps, e.g.
;; "[{:admin/email \"daniel@precursorapp.com\", :google-account/sub \"123\"} {:admin/email \"danny@precursorapp.com\", :google-account/sub \"345\"}]"
(defn admins []
  (if-let [admin-clj-str (System/getenv "ADMINS")]
    (edn/read-string admin-clj-str)
    []))

(defn prcrsr-bot-email []
  "prcrsr-bot@prcrsr.com")

(defn mailchimp-api-key []
  (System/getenv "MAILCHIMP_API_KEY"))

(defn mailchimp-list-id []
  (System/getenv "MAILCHIMP_LIST_ID"))

(defn mailgun-base-url []
  "https://api.mailgun.net/v2/mail.prcrsr.com/")

(defn mailgun-api-key []
  (System/getenv "MAILGUN_API_KEY"))

(defn mixpanel-api-token []
  (System/getenv "MIXPANEL_API_TOKEN"))

(defn mixpanel-api-key []
  (System/getenv "MIXPANEL_API_KEY"))

(defn mixpanel-api-secret []
  (System/getenv "MIXPANEL_API_SECRET"))

(defn product-hunt-api-token []
  (System/getenv "PRODUCT_HUNT_API_TOKEN"))

(defn rollbar-token []
  (System/getenv "ROLLBAR_TOKEN"))

(defn rollbar-client-token []
  (System/getenv "ROLLBAR_CLIENT_TOKEN"))

(defn stack-exchange-api-key []
  (System/getenv "STACK_EXCHANGE_API_KEY"))

(defn librato-api-name []
  "daniel+start-trial@prcrsr.com")

(defn librato-api-token []
  (System/getenv "LIBRATO_API_TOKEN"))

(defn stripe-secret-key []
  (let [env-key (System/getenv "STRIPE_SECRET_KEY")]
    (when (prod?)
      (assert env-key "Have to provide an STRIPE_SECRET_KEY in prod!"))
    env-key))

(defn stripe-publishable-key []
  (let [env-key (System/getenv "STRIPE_PUBLISHABLE_KEY")]
    (when (prod?)
      (assert env-key "Have to provide an STRIPE_PUBLISHABLE_KEY in prod!"))
    env-key))

(defn twilio-sid []
  (System/getenv "TWILIO_SID"))

(defn twilio-auth-token []
  (System/getenv "TWILIO_AUTH_TOKEN"))

(defn twilio-phone-number []
  "+19207538241")
