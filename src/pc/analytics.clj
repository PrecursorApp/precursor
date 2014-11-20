(ns pc.analytics
  (:require [pc.mixpanel :as mixpanel]))

(defn track-signup [cust ring-req]
  (mixpanel/alias (mixpanel/distinct-id-from-cookie ring-req) (:cust/uuid cust))
  (mixpanel/track "$signup" (:cust/uuid cust)))

(defn track-login [cust]
  (mixpanel/track "Login" (:cust/uuid cust)))
