(ns pc.http.lb)

(defonce health-status (atom {:status :up}))

(defn healthy? []
  (= :up (:status @health-status)))

(defn health-check-response [req]
  {:status (if (healthy?) 200 500)
   :body (str (:status @health-status))})

;; TODO: these should be able to talk to the load balancer to add or remove themselves
;;       instead of just changing the health-check response
(defn remove-self-from-lb []
  (swap! health-status assoc :status :down))

(defn add-self-to-lb []
  (swap! health-status assoc :status :up))
