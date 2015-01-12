(ns frontend.analytics.rollbar)

(defn init [cust-uuid cust-email]
  (js/Rollbar.configure #js {:payload #js {:person #js {:email cust-email :id cust-uuid}}}))
