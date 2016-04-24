(ns pc.ses
  (:require [amazonica.aws.simpleemail :as ses]
            [amazonica.core]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [pc.profile :as profile]
            [postal.core :as postal])
  (:import [org.apache.commons.io IOUtils]
           [java.nio ByteBuffer]))

;; TODO: move to secrets
(def access-key-id "AKIAJZ4JN2PJKKP3JEKQ")
(def secret-access-key "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")

(def smtp-user "AKIAJKXY7DM6XYBDSGEQ")
(def smtp-pass "AgILvzCkVICmzyrLvLZh2CjVxAr2qwGPbM0c4LgkN070")

(def dev-whitelist-pattern
  (let [re-fragments ["dwwoelfel" "danny.newidea" "@prcrsr.com" "@precursorapp.com"]]
    (re-pattern (str/join "|" re-fragments))))

(defn filter-addresses [to-addresses]
  (if (profile/use-email-whitelist?)
    (filter #(re-find dev-whitelist-pattern %) to-addresses)
    to-addresses))

(defn send-message
  "Attachments should be an array of maps with mandatory keys :content
   and optional keys :content-type, :file-name, and :description"
  [{:keys [from to cc bcc subject text html attachments] :as props}]
  (when-let [to-addresses (seq (filter-addresses [to]))]
    (postal/send-message {:user (profile/ses-smtp-user)
                          :pass (profile/ses-smtp-pass)
                          :host (profile/ses-smtp-host)
                          :port 587
                          :tls true}
                         {:from from
                          :to to-addresses
                          :bcc (concat (when (profile/bcc-audit-log?)
                                         ["audit-log@precursorapp.com"])
                                       (when bcc [bcc]))
                          :cc cc
                          :subject subject
                          :body (concat [:alternative
                                         {:type "text/plain; charset=utf-8"
                                          :content text}
                                         {:type "text/html; charset=utf-8"
                                          :content html}]
                                        (for [attachment attachments]
                                          (merge {:type :attachment}
                                                 attachment)))})))

;; keeping for the future--about 4x as fast as postal, but no support for attachments
;; Just need to figure out how to construct a raw message
(defn send-message-ses [{:keys [from to cc bcc subject text html] :as props}]
  (amazonica.core/with-credential [(profile/ses-access-key-id)
                                   (profile/ses-secret-access-key)
                                   (profile/s3-region)]
    (when-let [to-addresses (seq (filter-addresses [to]))]
      (ses/send-email :destination (merge {:to-addresses (vec to-addresses)
                                           :bcc-addresses (concat (when (profile/bcc-audit-log?)
                                                                    ["audit-log@precursorapp.com"])
                                                                  (when bcc [bcc]))}
                                          (when cc
                                            {:cc-addresses [cc]}))
                      :source from
                      :return-path "bounces@precursorapp.com"
                      :message {:subject subject
                                :body {:html html
                                       :text text}}))))
