(ns pc.mailchimp
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [slingshot.slingshot :refer (try+ throw+)]))

(def api-key "bd3e23ab33d2e93619efb9c8535d4d7b-us9")

(def list-id "cfc97d842f")

(defn api-endpoint [key method]
  (let [dc (last (re-find #"-(.+)" key))]
    (format "https://%s.api.mailchimp.com/2.0/%s.json" dc method)))

(defn list-subscribe [cust]
  (http/post (api-endpoint api-key "lists/subscribe")
             {:body (json/encode {:apikey api-key
                                  :id list-id
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
