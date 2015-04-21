(ns pc.http.routes.stripe
  (:require [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [defpage.core :as defpage :refer (defpage)]
            [pc.datomic :as pcd]
            [pc.datomic.web-peer :as web-peer]
            [pc.models.invoice :as invoice-model]
            [pc.models.plan :as plan-model]
            [pc.models.team :as team-model]
            [pc.rollbar :as rollbar]
            [pc.stripe :as stripe]
            [slingshot.slingshot :refer (try+ throw+)]))

;; Note: routes in this namespace have /hooks prepended to them by default
;;       We'll handle this with convention for now, but probably want to
;;       modify clout to use :uri instead of :path-info
;;       https://github.com/weavejester/clout/blob/master/src/clout/core.clj#L35

(defmacro with-hook-accounting [plan hook-json & body]
  `(let [hook-id# (get ~hook-json "id")
         t# @(d/transact (pcd/conn) [[:db/add (:db/id ~plan) :plan/stripe-event-ids hook-id#]])]
     ;; db.fn/cas would work well here, but not sure how to handle it with cardinality/many
     (when (and (not (contains? (:plan/stripe-event-ids (d/entity (:db-before t#) (:db/id ~plan)))
                                hook-id#))
                (contains? (:plan/stripe-event-ids (d/entity (:db-after t#) (:db/id ~plan)))
                           hook-id#))
       (try+
        ~@body
        (catch Object e#
          @(d/transact (pcd/conn) [[:db/retract (:db/id ~plan) :plan/stripe-event-ids hook-id#]])
          (throw+))))))

(defn handle-hook-dispatch-fn [hook-json]
  (get hook-json "type"))

(defmulti handle-hook handle-hook-dispatch-fn)

(defonce examples (atom {}))

(defmethod handle-hook :default
  [hook-json]
  (log/infof "%s hook from Stripe with no handler" (get hook-json "type"))
  (swap! examples assoc (get hook-json "type") hook-json))

(defmethod handle-hook "invoice.created"
  [hook-json]
  (let [db (pcd/default-db)
        _ (def myjson hook-json)
        plan (plan-model/find-by-stripe-customer db (get-in hook-json ["data" "object" "customer"]))]
    (with-hook-accounting plan hook-json
      (let [team (team-model/find-by-plan db plan)
            invoice-fields (get-in hook-json ["data" "object"])
            discount-fields (get-in invoice-fields "discount")
            invoice-id (d/tempid :db.part/user)]
        @(d/transact (pcd/conn) [{:db/id (d/tempid :db.part/tx)
                                  :transaction/broadcast true
                                  :transaction/team (:db/id team)}
                                 [:db/add (:db/id plan) :plan/invoices invoice-id]
                                 (web-peer/server-frontend-id invoice-id (:db/id team))
                                 (merge
                                  {:db/id invoice-id}
                                  (stripe/invoice-api->model invoice-fields)
                                  (when (seq discount-fields)
                                    (stripe/discount-api->model discount-fields)))])))))

(defmethod handle-hook "invoice.updated"
  [hook-json]
  (let [db (pcd/default-db)
        _ (def updatedjson hook-json)
        plan (plan-model/find-by-stripe-customer db (get-in hook-json ["data" "object" "customer"]))]
    (with-hook-accounting plan hook-json
      (let [team (team-model/find-by-plan db plan)
            invoice-fields (get-in hook-json ["data" "object"])
            invoice (invoice-model/find-by-stripe-id db (get invoice-fields "id"))
            ;; Stripe gives us a list of the keys that changed, we extract those and
            ;; do :db/retract from previous  and :db/add from new attrs
            before-values (pc.utils/inspect (-> (get hook-json "previous_attributes")
                                              stripe/invoice-api->model))
            after-values (stripe/invoice-api->model invoice-fields)]
        (when (seq before-values) ;; only if things we care about changed
          @(d/transact (pcd/conn) (reduce-kv (fn [acc k before-v]
                                               (when (not= before-v (get after-values k))
                                                 (concat acc
                                                         (when before-v
                                                           [[:db/retract (:db/id invoice) k before-v]])
                                                         (when (get after-values k)
                                                           [[:db/add (:db/id invoice) k (get after-values k)]])
                                                         )))
                                             [] before-values)))))))

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
