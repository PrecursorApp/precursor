(ns pc.http.datomic.common
  (:require [datomic.api :as d]
            [pc.datomic.schema :as schema]
            [pc.datomic.web-peer :as web-peer]))

;; TODO: is the transaction guaranteed to be the first? Can there be multiple?
(defn get-annotations [transaction]
  (let [txid (-> transaction :tx-data first :tx)]
    (d/entity (:db-after transaction) txid)))

(def outgoing-whitelist
  #{:layer/name
    :layer/uuid
    :layer/type
    :layer/start-x
    :layer/start-y
    :layer/end-x
    :layer/end-y
    :layer/rx
    :layer/ry
    :layer/fill
    :layer/stroke-width
    :layer/stroke-color
    :layer/opacity
    :layer/points-to

    :entity/type

    :layer/font-family
    :layer/text
    :layer/font-size
    :layer/path
    :layer/child
    :layer/ui-id
    :layer/ui-target
    :layer/document
    :session/uuid
    :document/uuid
    :document/name
    :document/creator
    :document/collaborators
    :document/privacy
    :chat/body
    :chat/color
    :chat/cust-name
    :chat/document
    :cust/uuid
    :cust/color-name
    :client/timestamp
    :server/timestamp

    :permission/document
    :permission/cust ;; translated
    :permission/permits
    :permission/grant-date
    :permission/team

    :access-grant/document
    :access-grant/email
    :access-grant/grant-date
    :access-grant/team

    :access-request/document
    :access-request/cust ;; translated
    :access-request/status
    :access-request/create-date
    :access-request/deny-date
    :access-request/team

    ;; TODO: remove when fully deployed
    :document/id
    })

(defn translate-datom-dispatch-fn [db d] (:a d))

(defmulti translate-datom translate-datom-dispatch-fn)

(defmethod translate-datom :default [db d]
  d)

(defmethod translate-datom :permission/cust-ref [db d]
  (-> d
    (assoc :a :permission/cust)
    (update-in [:v] #(:cust/email (d/entity db %)))))

(defmethod translate-datom :access-request/cust-ref [db d]
  (-> d
    (assoc :a :access-request/cust)
    (update-in [:v] #(:cust/email (d/entity db %)))))

(defmethod translate-datom :permission/document-ref [db d]
  (-> d
    (assoc :a :permission/document)))

(defmethod translate-datom :access-request/document-ref [db d]
  (-> d
    (assoc :a :access-request/document)))

(defmethod translate-datom :access-grant/document-ref [db d]
  (-> d
    (assoc :a :access-grant/document)))

(defmethod translate-datom :permission/team [db d]
  (-> d
    (assoc :v (:team/uuid (d/entity db (:v d))))))

(defmethod translate-datom :access-request/team [db d]
  (-> d
    (assoc :v (:team/uuid (d/entity db (:v d))))))

(defmethod translate-datom :access-grant/team [db d]
  (-> d
    (assoc :v (:team/uuid (d/entity db (:v d))))))

(defmethod translate-datom :layer/points-to [db d]
  (-> d
    (assoc :v (web-peer/client-id db (:v d)))))

(defn datom-read-api [db datom]
  (let [{:keys [e a v tx added] :as d} datom
        a (schema/get-ident a)
        v (if (contains? (schema/ident-ids) v)
            (schema/get-ident v)
            v)
        e (web-peer/client-id db e)]
    (->> {:e e :a a :v v :tx tx :added added}
      (translate-datom db))))

(defn whitelisted? [datom]
  (contains? outgoing-whitelist (:a datom)))

(defn frontend-document-transaction [transaction]
  (let [annotations (get-annotations transaction)]
    (when (and (:transaction/document annotations)
               (:transaction/broadcast annotations))
      (when-let [public-datoms (->> transaction
                                 :tx-data
                                 (filter #(:frontend/id (d/entity (:db-after transaction) (:e %))))
                                 (map (partial datom-read-api (:db-after transaction)))
                                 (filter whitelisted?)
                                 seq)]
        (merge {:tx-data public-datoms}
               annotations)))))

(defn frontend-team-transaction [transaction]
  (let [annotations (get-annotations transaction)]
    (when (and (:transaction/team annotations)
               (:transaction/broadcast annotations))
      (when-let [public-datoms (->> transaction
                                 :tx-data
                                 (filter #(:frontend/id (d/entity (:db-after transaction) (:e %))))
                                 (map (partial datom-read-api (:db-after transaction)))
                                 (filter whitelisted?)
                                 seq)]
        (merge {:tx-data public-datoms}
               annotations)))))
