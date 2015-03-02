(ns pc.ses
  (:require [amazonica.aws.simpleemail :as ses]
            [amazonica.core]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [pc.profile :as profile]))

;; TODO: move to secrets
(def access-key-id "AKIAJZ4JN2PJKKP3JEKQ")
(def secret-access-key "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")

(def dev-whitelist-pattern
  (let [re-fragments ["dwwoelfel" "danny.newidea" "@prcrsr.com" "@precursorapp.com"]]
    (re-pattern (str/join "|" re-fragments))))

(defn filter-to [to-addresses]
  (if (profile/use-email-whitelist?)
    (filter #(re-find dev-whitelist-pattern %) to-addresses)
    to-addresses))

(defn send-message [{:keys [from to cc bcc subject text html] :as props}]
  (amazonica.core/with-credential [access-key-id secret-access-key "us-west-2"]
    (ses/send-email :destination (merge {:to-addresses (filter-to [to])
                                         :bcc-addresses (concat ["audit-log@precursorapp.com"]
                                                                (when bcc [bcc]))}
                                        (when cc
                                          {:cc-addresses [cc]}))
                    :source from
                    :return-path "bounces@precursorapp.com"
                    :message {:subject subject
                              :body {:html html
                                     :text text}})))
