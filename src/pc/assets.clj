(ns pc.assets
  (:require [amazonica.aws.s3 :as s3]
            [amazonica.core]
            [cemerick.url :as url]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.tools.reader.edn :as edn]
            [fs]
            [pantomime.mime :refer [mime-type-of]]
            [pc.profile]
            [pc.rollbar :as rollbar])
  (:import java.security.MessageDigest
           org.apache.commons.codec.binary.Hex
           org.apache.commons.io.IOUtils))

(def aws-access-key "AKIAJ6CLYJYRMXJGMMEQ")
(def aws-secret-key "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")

(defn md5
  "Takes a file-name and returns the MD5 encoded as a hex string
   Will throw an exception if asked to encode an empty file."
  [file-name]
  {:post [(not= "d41d8cd98f00b204e9800998ecf8427e" %)]}
  (->> file-name
    io/input-stream
    IOUtils/toByteArray
    (.digest (MessageDigest/getInstance "MD5"))
    Hex/encodeHexString))

(def cdn-bucket "prcrsr-cdn")
(def manifest-pointer-key "manifest-pointer")
(def cdn-base-url "https://dtwdl3ecuoduc.cloudfront.net")

(defonce asset-manifest (atom {}))

(defn cdn-url [manifest-value]
  (str cdn-base-url "/" (:s3-key manifest-value)))

(defn asset-path [path]
  (if (pc.profile/prod-assets?)
    (let [manifest-value (get @asset-manifest path)]
      (assert manifest-value)
      (cdn-url manifest-value))
    path))

(defn fetch-specific-manifest [bucket key]
  (amazonica.core/with-credential [aws-access-key aws-secret-key]
    (-> (s3/get-object :bucket-name bucket :key key)
      :object-content
      slurp
      edn/read-string)))

(defn fetch-manifest []
  (amazonica.core/with-credential [aws-access-key aws-secret-key]
    (-> (s3/get-object :bucket-name cdn-bucket :key manifest-pointer-key)
      :object-content
      slurp
      edn/read-string
      (#(fetch-specific-manifest (:s3-bucket %) (:s3-key %))))))

(defn validate-manifest! [manifest]
  (doseq [[path value] manifest]
    (assert (< 0 (count (:body (http/get (cdn-url value))))))))

(defn load-manifest! []
  (let [manifest (fetch-manifest)]
    (validate-manifest! manifest)
    (reset! asset-manifest manifest)))

(defn init []
  (when (pc.profile/prod-assets?)
    (log/info "loading manifest")
    (let [manifest (load-manifest!)]
      (log/infof "loaded %s" manifest))))

;; TODO: upload public directories
(def assets-directory "resources/public")
(def manifest-paths ["/cljs/production/sourcemap-frontend.map"
                     "/cljs/production/frontend.js"
                     "/css/app.css"])

(defn assetify [path md5]
  (let [i (.lastIndexOf path ".")]
    (str (subs path 0 i) "-" md5 "." (subs path (inc i) (count path)))))

(defn move-manifest-pointer [bucket key]
  (amazonica.core/with-credential [aws-access-key aws-secret-key]
    (let [bytes (.getBytes (pr-str {:s3-bucket bucket :s3-key key}))]
      (s3/put-object :bucket-name cdn-bucket
                     :key manifest-pointer-key
                     :input-stream (java.io.ByteArrayInputStream. bytes)
                     :metadata {:content-length (count bytes)
                                :content-type "application/json"
                                :cache-control "no-cache"}))))

(defn update-sourcemap-url [root path]
  (let [js-full-path (str root path)
        file-string (slurp js-full-path)
        mapping-re #"(\/\/# sourceMappingURL=)(.+)"
        map-path (last (re-find mapping-re file-string))
        md5 (md5 (str (fs/dirname js-full-path) "/" map-path))]
    (spit js-full-path (str/replace file-string mapping-re (fn [[_ start _]]
                                                             (str start (assetify map-path md5)))))))

(defn upload-manifest [sha1]
  (update-sourcemap-url assets-directory "/cljs/production/frontend.js")
  (amazonica.core/with-credential [aws-access-key aws-secret-key]
    (let [manifest-key (str "releases/" sha1)
          manifest (reduce (fn [acc path]
                             (let [file-path (str assets-directory path)
                                   md5 (md5 file-path)
                                   ;; TODO: figure out a better way to handle leading slashes
                                   key (assetify (subs path 1) md5)]
                               (log/infof "pushing %s to %s" file-path key)
                               (s3/put-object :bucket-name cdn-bucket :key key :file file-path
                                              :metadata {:content-type (mime-type-of file-path)
                                                         :cache-control "max-age=3155692"})
                               (assoc acc path {:s3-key key :s3-bucket cdn-bucket})))
                           {} manifest-paths)
          manifest-bytes (.getBytes (pr-str manifest) "UTF-8")]
      (log/infof "uploading manifest to %s: %s" manifest-key manifest)
      (s3/put-object :bucket-name cdn-bucket
                     :key manifest-key
                     :input-stream (java.io.ByteArrayInputStream. manifest-bytes)
                     :metadata {:content-length (count manifest-bytes)
                                :content-type "application/edn"})
      (validate-manifest! (fetch-specific-manifest cdn-bucket manifest-key))
      (log/infof "moving manifest pointer to %s" manifest-key)
      (move-manifest-pointer cdn-bucket manifest-key))))

;; single agent to prevent dos
(defonce reload-agent (agent nil :error-handler (fn [a e]
                                                  (log/error "Error in reload-agent" e)
                                                  (.printStackTrace e)
                                                  (rollbar/report-exception e))))

(defn reload-assets []
  (send-off reload-agent (fn [a]
                           (let [manifest (load-manifest!)]
                             (log/infof "reloaded frontend assets to %s" manifest)))))
