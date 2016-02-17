(ns pc.mailgun
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [pc.profile]))

(def base-url pc.profile/mailgun-base-url)

(def api-key pc.profile/mailgun-api-key)

(defn send-message [{:keys [from to cc bcc subject text html] :as props}]
  (http/post (str (base-url) "messages") {:basic-auth ["api" (api-key)]
                                          :form-params props}))
