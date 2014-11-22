(ns pc.mailgun
  (:require [cheshire.core :as json]
            [clj-http.client :as http]))

(def base-url "https://api.mailgun.net/v2/mail.prcrsr.com/")

;; TODO: move api-key to secrets
(def api-key "key-1f7049aef5d463d6b6f3c204d038adef")

(defn send-message [{:keys [from to cc bcc subject text html] :as props}]
  (http/post (str base-url "messages") {:basic-auth ["api" api-key]
                                        :form-params props}))
