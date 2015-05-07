(ns pc.http.datomic2
  (:require [clojure.core.async :as async]
            [clojure.set :as set]
            [clojure.tools.logging :as log]
            [datomic.api :refer [db q] :as d]
            [pc.datomic :as pcd]
            [pc.datomic.web-peer :as web-peer]
            [pc.models.issue :as issue-model])
  (:import [java.util UUID]))


(defn get-float-attrs [db]
  (set (map last (d/q '{:find [?t ?i]
                        :where [[?t :db/valueType :db.type/float]
                                [?t :db/ident ?i]]}
                      db))))

(defn get-uuid-attrs [db]
  (set (map last (d/q '{:find [?t ?i]
                        :where [[?t :db/valueType :db.type/uuid]
                                [?t :db/ident ?i]]}
                      db))))

(defn coerce-floats [float-attrs [type e a v :as transaction]]
  (if (contains? float-attrs a)
    [type e a (float v)]
    transaction))

(defn coerce-uuids [uuid-attrs [type e a v :as transaction]]
  (if (and (contains? uuid-attrs a) (string? v))
    [type e a (UUID/fromString v)]
    transaction))

(defn coerce-server-timestamp [server-timestamp [type e a v :as transaction]]
  (if (and (= :db/add type) (= :server/timestamp a))
    [type e a server-timestamp]
    transaction))

(defn coerce-session-uuid [session-uuid [type e a v :as transaction]]
  (if (= :session/uuid a)
    [type e a session-uuid]
    transaction))

(defn coerce-cust-uuid [cust-uuid [type e a v :as transaction]]
  (if (= :cust/uuid a)
    [type e a cust-uuid]
    transaction))

(def read-scope-whitelist
  #{:session/uuid
    :chat/document
    :chat/body
    :chat/color
    :cust/uuid
    :client/timestamp
    :server/timestamp})

(defn incoming-whitelist [scope]
  (case scope
    :read read-scope-whitelist
    :admin (set/union read-scope-whitelist
                      #{:layer/name
                        :layer/uuid
                        :layer/type
                        :layer/start-x
                        :layer/start-y
                        :layer/end-x
                        :layer/end-y
                        :layer/rx
                        :layer/ry
                        :layer/stroke-width
                        :layer/stroke-color
                        :layer/opacity
                        :layer/start-sx
                        :layer/start-sy
                        :layer/fill
                        :layer/font-family
                        :layer/text
                        :layer/font-size
                        :layer/path
                        :layer/child
                        :layer/ui-id
                        :layer/ui-target
                        :layer/points-to
                        :layer/document
                        :document/name
                        :entity/type

                        :plan/billing-email})
    nil))

(defn whitelisted? [scope [type e a v :as transaction]]
  (contains? (incoming-whitelist scope) a))

(defn can-modify?
  "If the user has read scope, makes sure that they don't modify existing txes"
  [db document-id scope {:keys [remainder multiple]} [type e a v :as transaction]]
  (cond (= scope :admin) (contains? #{:db/add :db/retract} type)
        (= scope :read) (and (= remainder (mod e multiple))
                             (not (web-peer/taken-id? db document-id e))
                             (contains? #{:db/add :db/retract} type))))

(defn remove-float-conflicts [txes]
  (vals (reduce (fn [tx-index [type e a v :as tx]]
                  (let [index-key [e a v]]
                    (if (and (float? v) (contains? tx-index index-key))
                      (dissoc tx-index index-key)
                      (assoc tx-index index-key tx))))
                {} txes)))

(defn add-frontend-ids [document-id txes]
  (let [eid-map (zipmap (set (map second txes)) (repeatedly #(d/tempid :db.part/user)))
        frontend-id-txes (map (fn [[e tempid]] [:db/add tempid :frontend/id (UUID. document-id e)]) eid-map)]
    (concat (map (fn [tx]
                   (-> tx
                     (update-in [1] eid-map)
                     (#(if (= :layer/points-to (nth tx 2))
                         (update-in % [3] (fn [e]
                                            (or (get eid-map e)
                                                [:frontend/id (UUID. document-id e)])))
                         %))))
                 txes)
            frontend-id-txes)))

;; TODO: only let creators mark things as private
;; TODO: only let people on the white list make things as private

(defn transact!
  "Takes datoms from tx-data on the frontend and applies them to the backend. Expects datoms to be maps.
   Returns backend's version of the datoms."
  [datoms {:keys [document-id team-id client-id session-uuid cust-uuid access-scope frontend-id-seed receive-instant]}]
  (cond (empty? datoms)
        {:status 400 :body (pr-str {:error "datoms is required and should be non-empty"})}
        (< 1500 (count datoms))
        {:status 400 :body (pr-str {:error "You can only transact 1500 datoms at once"})}
        (not (or (number? document-id)
                 (number? team-id)))
        {:status 400 :body (pr-str {:error "document-id is required and should be an entity id"})}
        :else
        {:status 200
         :body {:datoms (let [db (pcd/default-db)
                              conn (pcd/conn)
                              txid (d/tempid :db.part/tx)
                              float-attrs (get-float-attrs db)
                              uuid-attrs (get-uuid-attrs db)
                              server-timestamp (or receive-instant (java.util.Date.))]
                          (some->> datoms
                            (map (comp (partial coerce-cust-uuid cust-uuid)
                                       (partial coerce-session-uuid session-uuid)
                                       (partial coerce-server-timestamp server-timestamp)
                                       (partial coerce-uuids uuid-attrs)
                                       (partial coerce-floats float-attrs)
                                       pcd/datom->transaction))
                            (filter (partial whitelisted? access-scope))
                            (filter (partial can-modify? db document-id access-scope frontend-id-seed))
                            (remove-float-conflicts)
                            (add-frontend-ids (or document-id team-id))
                            seq
                            (concat [(merge {:db/id txid
                                             :session/uuid session-uuid
                                             :session/client-id client-id
                                             :transaction/broadcast true}
                                            (when document-id {:transaction/document document-id})
                                            (when team-id {:transaction/team team-id})
                                            (when cust-uuid {:cust/uuid cust-uuid}))])
                            (d/transact conn)))}}))

(def issue-whitelist #{:vote/cust
                       :comment/body
                       :comment/author
                       :comment/created-at
                       :comment/parent
                       :issue/title
                       :issue/description
                       :issue/author
                       :issue/document
                       :issue/created-at
                       :issue/votes
                       :issue/comments
                       :frontend/issue-id})

(defn issue-whitelisted? [[type e a v :as tx]]
  (contains? issue-whitelist a))

(defn coerce-times [instant [type e a v :as transaction]]
  (if (contains? #{:issue/created-at :comment/created-at} a)
    [type e a instant]
    transaction))

(defn coerce-cust [cust [type e a v :as transaction]]
  (if (contains? #{:comment/author :issue/author :vote/cust} a)
    [type e a (:db/id cust)]
    transaction))

(def issue-append-only-attributes #{:vote/cust
                                    :comment/author
                                    :comment/created-at
                                    :frontend/issue-id
                                    :comment/parent
                                    :issue/author
                                    :issue/created-at
                                    :issue/document})

(def issue-author-only-attributes #{:vote/cust
                                    :comment/body
                                    :comment/author
                                    :comment/created-at
                                    :comment/parent
                                    :issue/title
                                    :issue/created-at
                                    :issue/description
                                    :issue/author
                                    :issue/document
                                    :frontend/issue-id})

(defn issue-can-modify? [db cust find-by-frontend-id-memo [type e a v :as transaction]]
  (and (vector? e)
       (= 2 (count e))
       (= :frontend/issue-id (first e))
       (or (not= :frontend/issue-id a) (not= :db/retract type))
       (contains? #{:db/add :db/retract} type)
       (let [frontend-id (second e)]
         (if-let [ent (find-by-frontend-id-memo db frontend-id)]
           (if (contains? issue-author-only-attributes a)
             (or (= (:db/id cust) (:db/id (:issue/author ent)))
                 (= (:db/id cust) (:db/id (:vote/cust ent)))
                 (= (:db/id cust) (:db/id (:comment/author ent))))
             (not (contains? issue-append-only-attributes a)))
           ;; new entity
           true))))

(defn type-check-new-entities [find-by-frontend-id-memo db txes]
  (doseq [[_ entity-txes] (vec (remove (fn [[[_ frontend-id] txes]]
                                         (find-by-frontend-id-memo db frontend-id))
                                       (group-by second txes)))]
    (reduce (fn [acc [type e a v]]
              (assoc acc a v))
            {} entity-txes))
  txes)

(defn add-vote-contraints [find-by-frontend-id-memo db txes]
  (let [vote-txes (filterv (fn [tx] (= :vote/cust (nth tx 2))) txes)]
    (concat txes
            (for [[_ e _ v] vote-txes
                  :let [issue-tx (first (filter #(and (= e (nth % 3))
                                                      (= :issue/votes (nth % 2)))
                                                txes))
                        frontend-id (second (second issue-tx))
                        issue-id (:db/id (find-by-frontend-id-memo db frontend-id))]]
              [:db/add e :vote/cust-issue (UUID. v issue-id)]))))

(defn all-frontend-ids [txes]
  (set/union (set (map (comp second second) txes))
             (reduce (fn [acc [type e a v]]
                       (if (vector? v)
                         (conj acc (second v))
                         acc))
                     #{} txes)))

(defn add-issue-frontend-ids [find-by-frontend-id-memo db txes]
  (let [id-map (zipmap (all-frontend-ids txes)
                       (repeatedly #(d/tempid :db.part/user)))
        ;; matches tempid with issue-id
        ;; TODO: should we use value for identity type and look up ids?
        frontend-id-txes (map (fn [[frontend-id tempid]] [:db/add tempid :frontend/issue-id frontend-id]) id-map)]
    (concat
     (map (fn [[type e a v]]
            [type
             (get id-map (second e)) ; replace lookup-ref with tempid
             a
             (if (vector? v) ; replace lookup-ref in value with tempid
               (get id-map (second v))
               v)])
          txes)
     frontend-id-txes)))

(defn add-document [cust txes]
  (reduce (fn [acc [type e a v :as tx]]
            (if (pc.utils/inspect (= :issue/document a))
              (let [doc-id (d/tempid :db.part/user)]
                (conj acc [type e a doc-id] {:db/id doc-id
                                             :document/creator (:cust/uuid cust)
                                             :document/privacy :document.privacy/read-only
                                             :document/name "Untitled"}))
              (conj acc tx)))
          [] txes))



;; TODO: generalize this--probably need to start setting that entity/type field
(defn issue-valid-entity? [ent]
  (or (issue-model/valid-issue? ent)
      (issue-model/valid-comment? ent)
      (issue-model/valid-vote? ent)
      (issue-model/valid-document? ent)))

(defn type-check-new-entities [db txes]
  (let [{:keys [tempids db-after]} (d/with db txes)]
    (doseq [id (vals tempids)
            :let [ent (d/entity db-after id)]]
      (assert (issue-valid-entity? ent)
              (format "issue-tx contained txes that would have created an invalid state in the db %s for %s" txes ent))))
  txes)

(defn debug-def [txes]
  (def debug-txes txes)
  txes)

(defn transact-issue!
  "Takes datoms from tx-data on the frontend and applies them to the backend. Expects datoms to be maps.
   Returns backend's version of the datoms."
  [datoms {:keys [client-id session-uuid cust receive-instant]}]
  (cond (empty? datoms)
        {:status 400 :body (pr-str {:error "datoms is required and should be non-empty"})}
        (< 1500 (count datoms))
        {:status 400 :body (pr-str {:error "You can only transact 1500 datoms at once"})}
        :else
        {:status 200
         :body {:datoms (let [db (pcd/default-db)
                              conn (pcd/conn)
                              txid (d/tempid :db.part/tx)
                              float-attrs (get-float-attrs db)
                              uuid-attrs (get-uuid-attrs db)
                              server-timestamp (or receive-instant (java.util.Date.))
                              find-by-frontend-id-memo (memoize (fn [db frontend-id]
                                                                  (d/entity db (:e (first (d/datoms db :avet :frontend/issue-id
                                                                                                    frontend-id))))))]
                          (some->> datoms
                            (map (comp (partial coerce-session-uuid session-uuid)
                                       (partial coerce-times server-timestamp)
                                       (partial coerce-uuids uuid-attrs)
                                       (partial coerce-floats float-attrs)
                                       (partial coerce-cust cust)
                                       pcd/datom->transaction))
                            (filter issue-whitelisted?)
                            (remove-float-conflicts)
                            (filter (partial issue-can-modify? db cust find-by-frontend-id-memo))
                            (add-vote-contraints find-by-frontend-id-memo db)
                            (add-issue-frontend-ids find-by-frontend-id-memo db)
                            (add-document cust)
                            seq
                            (type-check-new-entities db)
                            (concat [(merge {:db/id txid
                                             :session/uuid session-uuid
                                             :session/client-id client-id
                                             :transaction/broadcast true
                                             :transaction/issue-tx? true
                                             :cust/uuid (:cust/uuid cust)})])
                            (d/transact conn)
                            deref))}}))
