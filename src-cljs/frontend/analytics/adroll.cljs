(ns frontend.analytics.adroll
  (:require [frontend.utils :as utils :include-macros true]))

(defn record-payer []
  (utils/swallow-errors
   (js/__adroll.record_user #js {:adroll_segments "payer"})))
