(ns pc.http.clipboard
  (:require [amazonica.core]
            [amazonica.aws.s3 :as s3]
            [clj-time.core :as time]
            [crypto.random]
            [pc.profile :as profile])
  (:import [com.amazonaws HttpMethod]))

(defn create-presigned-clipboard-url [cust]
  (let [key (str (:cust/uuid cust) "/" (crypto.random/url-part 64))
        bucket (profile/clipboard-bucket)
        region "us-west-2"]
    (amazonica.core/with-credential [(profile/clipboard-s3-access-key)
                                     (profile/clipboard-s3-secret-key)
                                     region]
      {:url (str (s3/generate-presigned-url :bucket-name bucket
                                            :key key
                                            :expiration (time/plus (time/now) (time/hours 1))
                                            :method HttpMethod/PUT
                                            :content-type "image/svg+xml"))
       :bucket bucket
       :key key})))

(defn create-presigned-clip-url [clip]
  (let [bucket (profile/clipboard-bucket)
        region "us-west-2"]
    (amazonica.core/with-credential [(profile/clipboard-s3-access-key)
                                     (profile/clipboard-s3-secret-key)
                                     region]
      (str (s3/generate-presigned-url :bucket-name bucket
                                      :key (pc.utils/inspect (:clip/s3-key clip))
                                      :expiration (time/plus (time/now) (time/weeks 1))
                                      :method HttpMethod/GET)))))
