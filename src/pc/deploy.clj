(ns pc.deploy
  (:require [amazonica.aws.s3 :as s3]
            [amazonica.core]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [pc.util.md5 :as md5]
            [pc.profile])
  (:import java.util.UUID
           com.amazonaws.services.s3.model.AmazonS3Exception))

;; TODO: upload and assetify all of the sourcemap sources
;;       equivalent to a source-code dump, so give it some thought first

;; How the asset-manifest works:
;; manifest is text, points at the current release in the cdn
;; release is a jar, named pc-0.1.0-:sha1.jar

(def manifest-key "manifest")

(defn deploy-slug-key [sha1]
  ;; use the sha1 as a prefix to get better performance out of s3
  (str "releases/" sha1 "/pc-" sha1 "-standalone.jar"))

(defn fetch-manifest []
  (->> (s3/get-object :bucket-name (pc.profile/deploy-s3-bucket) :key manifest-key)
    :object-content
    slurp))

(defn move-manifest [bucket release-key]
  (let [bytes (.getBytes release-key "UTF-8")]
    (s3/put-object :bucket-name (pc.profile/deploy-s3-bucket)
                   :key manifest-key
                   :input-stream (java.io.ByteArrayInputStream. bytes)
                   :metadata {:content-length (count bytes)
                              :content-type "text/plain"
                              :cache-control "no-cache"})))

(defn validate-deploy-slug! [bucket key]
  (s3/get-object-metadata :bucket-name bucket
                          :key key))

(defn upload-slug [sha1]
  (pc.profile/init) ;; called as a task, so secrets may be empty
  (amazonica.core/with-credential [(pc.profile/deploy-aws-access-key)
                                   (pc.profile/deploy-aws-secret-key)]
    (let [key (deploy-slug-key sha1)]
      (log/infof "uploading %s" key)
      (s3/put-object :bucket-name (pc.profile/deploy-s3-bucket)
                     :key key
                     :file (io/file "target/pc-standalone.jar")
                     :metadata {:content-type "application/java-archive"
                                :cache-control "max-age=3155692"})
      (log/infof "validating upload")
      (validate-deploy-slug! (pc.profile/deploy-s3-bucket) key)
      (log/infof "moving manifest")
      (move-manifest (pc.profile/deploy-s3-bucket) key))))

(defn rollback-manifest [sha1]
  (amazonica.core/with-credential [(pc.profile/deploy-aws-access-key)
                                   (pc.profile/deploy-aws-secret-key)]
    (let [key (deploy-slug-key sha1)]
      (validate-deploy-slug! (pc.profile/deploy-s3-bucket) key)
      (move-manifest (pc.profile/deploy-s3-bucket) key))))
