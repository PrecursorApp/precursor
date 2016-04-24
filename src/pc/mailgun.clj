(ns pc.mailgun
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [pc.profile :as profile]))

(defn send-message [{:keys [from to cc bcc subject text html] :as props}]
  (http/post (str (profile/mailgun-base-url) "messages")
             {:basic-auth ["api" (profile/mailgun-api-key)]
              :form-params props}))
