(ns pc.profile
  "Keeps track of env-specific settings")

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

;; TODO: move to secrets
(def dev-session-key "9WOdevDR9bsnpFXJ")
(defn http-session-key []
  (let [env-key (System/getenv "HTTP_SESSION_KEY")]
    (when (prod?)
      (assert env-key "Have to provide a HTTP_SESSION_KEY in prod!"))
    (or env-key
        dev-session-key)))

(defn admin-http-session-key []
  (let [env-key (System/getenv "ADMIN_HTTP_SESSION_KEY")]
    (when (prod?)
      (assert env-key "Have to provide an ADMIN_HTTP_SESSION_KEY in prod!"))
    (or env-key
        dev-session-key)))

(defn env []
  (if (prod?)
    "production"
    "development"))

(defn datomic-uri []
  (System/getenv "DATOMIC_URI"))

(defn admin-datomic-uri []
  (System/getenv "ADMIN_DATOMIC_URI"))

(defn statsd-host []
  ;; goes to localhost if it can't resolve
  "10.99.0.104")

(defn use-email-whitelist?
  "Used to guard against sending emails to customers from dev-mode"
  []
  (not (prod?)))

(defn bcc-audit-log?
  "Determines whether to bcc audit-log@ on every email"
  []
  (prod?))

(defn allow-mismatched-servername? []
  (not (prod?)))

(defn register-twilio-callbacks? []
  (prod?))
