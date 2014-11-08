(ns pc.profile
  "Keeps track of env-specific settings")

(defn prod? []
  (= "true" (System/getenv "PRODUCTION")))

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
