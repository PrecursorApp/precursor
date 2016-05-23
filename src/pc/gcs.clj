(ns pc.gcs
  "Google Cloud Storage"
  (:require [cheshire.core :as json]
            [clj-pgp.message :as pgp]
            [clj-time.core :as time]
            [clj-time.format :as time-format]
            [clojure.java.io :as io]
            [pc.profile]
            [pc.util.base64 :as base64]
            [hiccup.core :as h])
  (:import [java.io ByteArrayInputStream]
           [java.security KeyFactory PrivateKey Signature KeyStore]
           [java.security.spec PKCS8EncodedKeySpec]))

(defn access-id [] (pc.profile/gcs-access-id))

(defn string-to-sign [{:keys [verb content-md5 content-type expiration extension-headers resource]}]
  {:pre [verb expiration resource]}
  (str verb "\n"
       content-md5 "\n"
       content-type "\n"
       expiration "\n"
       extension-headers
       resource))

(defn generate-policy-document [{:keys [expiration bucket object-prefix acl]
                                 :or {acl "public-read"}}]
  (base64/encode
   (json/encode {:expiration (time-format/unparse (:date-time time-format/formatters) expiration)
                 :conditions [["starts-with" "$key" object-prefix]
                              {:acl acl}
                              {:bucket bucket}]})))

(defn get-key []
  (let [ks (KeyStore/getInstance "PKCS12")
        key-stream (ByteArrayInputStream. (pgp/decrypt (slurp (io/resource "google-key.p12.gpg"))
                                                       (pc.profile/gcs-passphrase)))]
    (.load ks key-stream (char-array "notasecret"))
    (.getKey ks "privatekey" (char-array "notasecret"))))

(defn sign [string-to-sign]
  (let [s (Signature/getInstance "SHA256withRSA")]
    (.initSign s (get-key))
    (.update s (.getBytes string-to-sign "UTF-8"))
    (base64/encode (.sign s))))
