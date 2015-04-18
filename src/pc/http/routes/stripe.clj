(ns pc.http.routes.stripe
  (:require [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [defpage.core :as defpage :refer (defpage)]
            [pc.rollbar :as rollbar]
            [pc.stripe :as stripe]))

;; Note: routes in this namespace have /hooks prepended to them by default
;;       We'll handle this with convention for now, but probably want to
;;       modify clout to use :uri instead of :path-info
;;       https://github.com/weavejester/clout/blob/master/src/clout/core.clj#L35

(defn handle-hook-dispatch-fn [hook-json]
  (get hook-json "type"))

(defmulti handle-hook handle-hook-dispatch-fn)

(defonce examples (atom {}))

(defmethod handle-hook :default
  [hook-json]
  (log/infof "%s hook from Stripe with no handler" (get hook-json "type"))
  (swap! examples assoc (get hook-json "type") hook-json))

;; /hooks/stripe
(defpage webhook [:post "/stripe"] [req]
  (-> req
    :body
    slurp
    json/decode
    (get "id")
    (stripe/fetch-event)
    handle-hook)
  ;; todo: handle replay attacks
  {:status 200})

(def hooks-app (defpage/collect-routes))
