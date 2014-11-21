(ns pc.email
  (:require [clojure.string :as str]
            [pc.mailgun :as mailgun]))

(defn send-chat-invite [{:keys [cust to-email doc-id]}]
  (mailgun/send-message {:from "Precursor <draw@prcrsr.com>"
                         :to to-email
                         :subject (str/trim (str (:cust/first-name cust)
                                                 (when (and (:cust/first-name cust)
                                                            (:cust/last-name cust))
                                                   (str " " (:cust/last-name cust)))
                                                 " "
                                                 (cond (and (not (:cust/last-name cust))
                                                            (:cust/first-name cust))
                                                       (str "(" (:cust/email cust) ") ")

                                                       (not (:cust/first-name cust))
                                                       (str (:cust/email cust) " ")

                                                       :else nil)
                                                 "invited you to a document on Precursor"))
                         :text (str "Hey there,\nCome draw with me on Precursor: https://prcrsr.com/document" doc-id)
                         :html (format "<html><body><div>Hey there,</div><br /><div>Come draw with me on Precursor: <a href=\"https://prcrsr.com/document/%s\">https://prcrsr.com/document/%s</a></div></body></html>" doc-id doc-id)}))
