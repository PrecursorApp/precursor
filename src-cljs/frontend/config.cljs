(ns frontend.config)

(def subdomain (aget js/window "Precursor" "subdomain"))
(def hostname (aget js/window "Precursor" "hostname"))
