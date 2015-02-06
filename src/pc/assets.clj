(ns pc.assets
  (:require [amazonica.aws.s3 :as s3]
            [amazonica.aws.cloudfront :as cloudfront]
            [amazonica.core]
            [cemerick.url :as url]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.tools.reader.edn :as edn]
            [fs]
            [pantomime.mime :refer [mime-type-of]]
            [pc.util.md5 :as md5]
            [pc.gzip :as gzip]
            [pc.profile]
            [pc.rollbar :as rollbar]
            [slingshot.slingshot :refer (try+ throw+)])
  (:import java.util.UUID
           com.amazonaws.services.s3.model.AmazonS3Exception))

;; TODO: upload and assetify all of the sourcemap sources
;;       equivalent to a source-code dump, so give it some thought first

;; How the asset-manifest works:
;; https://prcrsr.com/document/17592193885179
;; manifest-pointer is edn, points at the current manifest in the cdn
;; manifest is edn, maps local files to their keys in s3
;; keys are assetified by the md5 of their content (frontend.js -> frontend-:md5.js)
;; Cloudfront urls match s3 keys (e.g. frontend-:md5.js -> https://dt...cloudfront.net/frontend-:md5.js)

#_(t/def-alias ManifestPointer (HMap :mandatory {:s3-bucket String
                                                 :s3-key String}))

#_(t/def-alias Asset (HMap :mandatory {:s3-bucket String
                                       :s3-key String}))

#_(t/def-alias Manifest (HMap :mandatory {:s3-bucket String
                                          :s3-key String
                                          :assets (IPersistentMap (t/Map String Asset))}))

(def aws-access-key "AKIAJ6CLYJYRMXJGMMEQ")
(def aws-secret-key "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")

(def cdn-bucket "prcrsr-cdn")
(def manifest-pointer-key "manifest-pointer")
(def cdn-base-url "https://dtwdl3ecuoduc.cloudfront.net")
(def cdn-distribution-id "E2IH3R3S5KPXM6")

(defonce asset-manifest (atom {}))
(defn asset-manifest-version [] (get @asset-manifest :code-version))

(defn cdn-url [manifest-value]
  (str cdn-base-url "/" (:s3-key manifest-value)))

(defn manifest-asset-path [manifest path]
  (let [manifest-value (get-in manifest [:assets path])]
    (assert manifest-value)
    (cdn-url manifest-value)))

(defn asset-path [path & {:keys [manifest]
                          :or {manifest @asset-manifest}}]
  (if (pc.profile/prod-assets?)
    (manifest-asset-path manifest path)
    path))

;; TODO: make the caller set credentials
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
  (doseq [[path value] (:assets manifest)]
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
        md5 (md5/md5 (str (fs/dirname js-full-path) "/" map-path))]
    (spit js-full-path (str/replace file-string mapping-re (fn [[_ start _]]
                                                             (str start (assetify map-path md5)))))))

(defn upload-sourcemap [sha1 manifest]
  (let [source-map "resources/public/cljs/production/sourcemap-frontend.map"
        sources (-> source-map slurp json/decode (get "sources"))]
    (http/post "https://api.rollbar.com/api/1/sourcemap"
               {:multipart (concat [{:name "access_token" :content rollbar/rollbar-prod-token}
                                    {:name "version" :content sha1}
                                    {:name "minified_url" :content (manifest-asset-path manifest "/cljs/production/frontend.js")}
                                    {:name "source_map" :content (io/file source-map)}]
                                   (for [source sources]
                                     (do (println source)
                                         {:name source :content (io/file (fs/join (fs/dirname source-map) source))})))})))

(defn make-manifest-key [sha1]
  (str "releases/" sha1))

(defn public-files []
  (remove #(.isDirectory %)
          (tree-seq (fn [f] (and (.isDirectory f)
                                 (not= f (io/file "resources/public/cljs"))
                                 ;; css gets assetified and uploaded separately
                                 (not= f (io/file "resources/public/css"))))
                    (fn [f] (seq (.listFiles f)))
                    (io/file "resources/public"))))

(defn upload-public []
  ;; TODO: traverse subdirectories
  ;; TODO: detect if we've already uploaded an asset and invalidate it
  (let [invalidations (atom [])]
    (doseq [file (public-files)
            :when (not= \. (first (.getName file)))
            :let [key (str/replace-first (str file) "resources/public/" "")
                  gzipped-bytes (gzip/gzip file)
                  tag (md5/byte-array->md5 gzipped-bytes)]]
      (let [existing (try+
                      (s3/get-object-metadata :bucket-name cdn-bucket :key key)
                      (catch AmazonS3Exception e
                        (if (-> e amazonica.core/ex->map :status-code (= 403))
                          nil
                          (throw+))))]
        (if (= tag (:etag existing))
          (log/infof "already uploaded %s to %s" (str file) key)
          (let [_ (log/infof "uploading %s to %s" (str file) key)
                res (s3/put-object :bucket-name cdn-bucket
                                   :key key
                                   :input-stream (java.io.ByteArrayInputStream. gzipped-bytes)
                                   :metadata {:content-type (mime-type-of file)
                                              :content-md5 tag
                                              :content-encoding "gzip"
                                              :content-length (count gzipped-bytes)
                                              :cache-control "max-age=3155692"})]
            (log/infof "uploaded %s" res)
            (assert (= tag (:etag res)))
            (when existing
              (swap! invalidations conj key))))))
    (when (seq @invalidations)
      (log/infof "invalidationg %s" @invalidations)
      (let [items (mapv #(str "/" %) @invalidations)
            res (cloudfront/create-invalidation :distribution-id cdn-distribution-id
                                                :invalidation-batch {:paths {:items items
                                                                             :quantity (count items)}
                                                                     :caller-reference (str (UUID/randomUUID))})]
        (log/infof "created invalidation %s" res)))))

(defn upload-manifest [sha1]
  ;; TODO: this is dumb, we shouldn't write to the file
  (update-sourcemap-url assets-directory "/cljs/production/frontend.js")
  (amazonica.core/with-credential [aws-access-key aws-secret-key]
    (upload-public)
    (let [manifest-key (make-manifest-key sha1)
          assets (reduce (fn [acc path]
                           (let [file-path (str assets-directory path)
                                 md5 (md5/md5 file-path)
                                 gzipped-bytes (gzip/gzip file-path)
                                 ;; TODO: figure out a better way to handle leading slashes
                                 key (assetify (subs path 1) md5)]
                             (log/infof "pushing %s to %s" file-path key)
                             (s3/put-object :bucket-name cdn-bucket
                                            :key key
                                            :input-stream (java.io.ByteArrayInputStream. gzipped-bytes)
                                            :metadata {:content-type (mime-type-of file-path)
                                                       :content-length (count gzipped-bytes)
                                                       :content-md5 (md5/byte-array->md5 gzipped-bytes)
                                                       :content-encoding "gzip"
                                                       :cache-control "max-age=3155692"})
                             (assoc acc path {:s3-key key :s3-bucket cdn-bucket})))
                         {} manifest-paths)
          manifest {:assets assets :code-version sha1}
          manifest-bytes (.getBytes (pr-str manifest)
                                    "UTF-8")]
      (log/infof "uploading manifest to %s: %s" manifest-key manifest)
      (s3/put-object :bucket-name cdn-bucket
                     :key manifest-key
                     :input-stream (java.io.ByteArrayInputStream. manifest-bytes)
                     :metadata {:content-length (count manifest-bytes)
                                :content-type "application/edn"})
      (validate-manifest! (fetch-specific-manifest cdn-bucket manifest-key))
      (log/infof "moving manifest pointer to %s" manifest-key)
      (move-manifest-pointer cdn-bucket manifest-key)
      (log/infof "uploading sourcemap to rollbar")
      (upload-sourcemap sha1 manifest))))

;; single agent to prevent dos
(defonce reload-agent (agent nil :error-handler (fn [a e]
                                                  (log/error "Error in reload-agent" e)
                                                  (.printStackTrace e)
                                                  (rollbar/report-exception e))))

(defn reload-assets []
  (send-off reload-agent (fn [a]
                           (let [manifest (load-manifest!)]
                             (log/infof "reloaded frontend assets to %s" manifest)))))

(defn rollback-manifest [sha1]
  (let [manifest-key (make-manifest-key sha1)]
    (validate-manifest! (fetch-specific-manifest cdn-bucket manifest-key))
    (move-manifest-pointer cdn-bucket manifest-key)))
