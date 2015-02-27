(ns pc.ses
  (:require [amazonica.aws.simpleemail :as ses]
            [amazonica.core]
            [cheshire.core :as json]
            [clj-http.client :as http]))

;; TODO: move to secrets
(def access-key-id "AKIAJZ4JN2PJKKP3JEKQ")
(def secret-access-key "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")

(defn send-message [{:keys [from to cc bcc subject text html] :as props}]
  (amazonica.core/with-credential [access-key-id secret-access-key "us-west-2"]
    (ses/send-email :destination (merge {:to-addresses [to]
                                         :bcc-addresses (concat ["audit-log@precursorapp.com"]
                                                                (when bcc [bcc]))}
                                        (when cc
                                          {:cc-addresses [cc]}))
                    :source from
                    :return-path "bounces@precursorapp.com"
                    :message {:subject subject
                              :body {:html html
                                     :text text}})))
