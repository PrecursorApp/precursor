(ns pc.views.invoice
  (:require [clj-pdf.core :as pdf]
            [clj-time.coerce]
            [clj-time.format]
            [clojure.java.io :as io]
            [pc.datomic.web-peer :as web-peer]
            [pc.http.urls :as urls]))


(def styles {:link {:color [6 155 250]}})

(defn invoice-pdf [team invoice out]
  (pdf/pdf
   [{:title (str "Precursor invoice for the " (:team/subdomain team) " team")
     :size "a4"
     :footer {:page-numbers false}
     :stylesheet styles}
    [:image {:width 150
             :height 100}
     (io/resource "public/img/precursor-banner-small.png")]

    [:heading "Precursor Invoice #" (web-peer/client-id invoice)]

    [:line]
    [:spacer 1]

    [:paragraph
     "Thanks for your business! If you have any questions, please email us at "
     [:anchor.link {:target "mailto:support@precursorapp.com"}
      "support@precursorapp.com"]
     "."]


    [:table
     ["Date" (clj-time.format/unparse (clj-time.format/formatter "E, d MMM y")
                                      (clj-time.coerce/from-date (:invoice/date invoice)))]
     ["Subtotal" (format "$%.2f" (float (/ (:invoice/subtotal invoice) 100)))]
     ["Total" (format "$%.2f" (float (/ (:invoice/total invoice) 100)))]]

    [:spacer 1]

    [:paragraph
     "You can see more details on "
     [:anchor.link {:target (urls/from-doc (:team/intro-doc team)
                                           :query {:overlay "plan"})}
      "your team's plan page"]
     "."]]
   out))

(comment
  (let [team (pc.models.team/find-by-subdomain (pc.datomic/default-db) "precursor")]
    (invoice-pdf team (first (:plan/invoices (:team/plan team)))
                 "test.pdf")))
