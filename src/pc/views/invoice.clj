(ns pc.views.invoice
  (:require [clj-pdf.core :as pdf]
            [clj-time.coerce]
            [clj-time.format]
            [clojure.java.io :as io]
            [datomic.api :as d]
            [pc.datomic :as pcd]
            [pc.datomic.web-peer :as web-peer]
            [pc.http.urls :as urls])
  (:import [java.io ByteArrayOutputStream]))


;; Docs on building pdfs with clj-pdf:
;; https://github.com/yogthos/clj-pdf

(def styles {:link {:color [6 155 250]}})

(defn format-invoice-date [instant]
  (clj-time.format/unparse (clj-time.format/formatter "E, d MMM y")
                           (clj-time.coerce/from-date instant)))

(defn format-stripe-cents
  "Formats Stripe's currency values into ordinary dollar format
   500 -> $5
   489 -> $4.89"
  [cents]
  (let [abs-cents (Math/abs cents)
        pennies (mod abs-cents 100)
        dollars (/ (- abs-cents pennies) 100)]
    (if (pos? pennies)
      (format "$%s%d.%02d" (if (neg? cents) "-" "") dollars pennies)
      (format "$%s%d" (if (neg? cents) "-" "") dollars))))

(defn render-pdf [db team invoice out]
  (pdf/pdf
   [{:title (str "Precursor invoice for the " (:team/subdomain team) " team")
     :size "a4"
     :footer {:page-numbers false}
     :stylesheet styles}

    [:heading "Precursor Invoice #" (web-peer/client-id invoice)]

    [:line]
    [:spacer 1]

    [:paragraph
     "Thanks for your business! If you have any questions, please email us at "
     [:anchor.link {:target "mailto:support@precursorapp.com"}
      "support@precursorapp.com"]
     "."]

    [:table {:border false
             :widths [1 4]}
     ["Date" (format-invoice-date (:invoice/date invoice))]
     ["Period" (str (format-invoice-date (:invoice/period-start invoice))
                    " to "
                    (format-invoice-date (:invoice/period-end invoice)))]
     ["Team" (:team/subdomain team)]
     ["Description" (:invoice/description invoice "Team subscription")]

     (when (:discount/coupon invoice)
       (let [coupon (d/entity db (:discount/coupon invoice))]
         ["Discount" (format "%s%% off for %s months"
                             (:coupon/percent-off coupon)
                             (:coupon/duration-in-months coupon))]))
     ["Total Charge" (format-stripe-cents (:invoice/total invoice))]
     (when (neg? (:invoice/total invoice))
       ["Credit" (format-stripe-cents (- (:invoice/total invoice)))])]

    [:spacer 1]

    [:paragraph
     "You can see more details on "
     [:anchor.link {:target (urls/from-doc (:team/intro-doc team)
                                           :query {:overlay "plan"})}
      "your team's plan page"]
     "."]]
   out))

(defn invoice-pdf [db team invoice]
  (let [out (ByteArrayOutputStream.)]
    (render-pdf db team invoice out)
    (.toByteArray out)))
