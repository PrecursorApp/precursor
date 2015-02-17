(ns pc.datomic.web-peer
  (:require [datomic.api :as d])
  (:import java.util.UUID))

;; This is a datascript limitation. 1/2 billion is enough per doc
;; https://github.com/tonsky/datascript/issues/56#issuecomment-73424622
(def min-client-part 1)
(def max-client-part 0x20000000)

(defn datoms-for-ns [db namespace-part]
  (seq (d/index-range db
                      :frontend/id
                      (UUID. namespace-part min-client-part)
                      ;; Using max long here b/c legacy entities
                      ;; use the entity id for the client part.
                      ;; Revisit this in the future, and possibly
                      ;; migrate everything down.
                      (UUID. namespace-part Long/MAX_VALUE))))

(defn namespace-part [frontend-id]
  (.getMostSignificantBits frontend-id))

(defn client-part [frontend-id]
  (.getLeastSignificantBits frontend-id))

(defn client-parts-for-ns
  "Returns the client eids, should always be in order"
  [db namespace-part]
  (map (comp client-part :v) (datoms-for-ns db namespace-part)))

(def multiple 5000)
;; backend uses reserved-remainder to create client parts
(def reserved-remainder 0)
(def remainders (disj (set (range multiple)) reserved-remainder))

(defn client-id
  ([entity]
   (let [frontend-id (:frontend/id entity)]
     (assert frontend-id (format "%s does not have a frontend/id" (:db/id entity)))
     (client-part frontend-id)))
  ([db e]
   (client-id (d/entity db e))))

(defn server-frontend-id [entity-id namespace-part]
  [::assign-frontend-id entity-id namespace-part multiple reserved-remainder])
