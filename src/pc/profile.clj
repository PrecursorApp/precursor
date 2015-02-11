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

(defn https-port []
  (if (System/getenv "HTTPS_PORT")
    (Integer/parseInt (System/getenv "HTTPS_PORT"))
    (if (prod?) 443 8078)))

(defn force-ssl? []
  (prod?))

(defn hostname []
  (if (prod?)
    "prcrsr.com"
    "localhost"))

;; TODO: move to secrets
(def dev-session-key "9WOdevDR9bsnpFXJ")
(defn http-session-key []
  (or (System/getenv "HTTP_SESSION_KEY")
      dev-session-key))

(defn env []
  (if (prod?)
    "production"
    "development"))

;; TODO: remove migrating check after migration
(defn migrating-to-new-storage? []
  (= "true" (System/getenv "STORAGE_MIGRATION")))
