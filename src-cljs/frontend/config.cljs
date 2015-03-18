(ns frontend.config
  (:require [frontend.utils :as utils]))

(def subdomain (aget js/window "Precursor" "subdomain"))
(def hostname (aget js/window "Precursor" "hostname"))
(def scheme (.getScheme utils/parsed-uri))
(def port (.getPort utils/parsed-uri))
