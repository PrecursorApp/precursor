(ns pc.mailchimp
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [pc.profile]
            [slingshot.slingshot :refer (try+ throw+)]))

(defn api-endpoint [key method]
  (let [dc (last (re-find #"-(.+)" key))]
    (format "https://%s.api.mailchimp.com/2.0/%s.json" dc method)))

(defn list-subscribe [cust]
  (http/post (api-endpoint (pc.profile/mailchimp-api-key) "lists/subscribe")
             {:body (json/encode {:apikey (pc.profile/mailchimp-api-key)
                                  :id (pc.profile/mailchimp-list-id)
                                  :email {:email (:cust/email cust)}
                                  :double_optin false
                                  :merge_vars {"FNAME" (:cust/first-name cust)
                                               "LNAME" (:cust/last-name cust)}})}))

(defn maybe-list-subscribe
  "Subscribes customer to list, catches exception if already subscribed"
  [cust]
  (try+
   (list-subscribe cust)
   (catch [:status 500] t
     (if (some-> t :body json/decode (get "name") (= "List_AlreadySubscribed"))
       ::list-already-subscribed
       (throw+ t)))))
