(ns pc.replay
  (:require [clojure.set :as set]
            [datomic.api :as d]
            [pc.datomic :as pcd]
            [pc.datomic.schema :as schema]
            [pc.datomic.web-peer :as web-peer])
  (:import java.util.UUID))

(defn- ->datom
  [[e a v tx added]]
  {:e e :a a :v v :tx tx :added added})

(defn tx-data [transaction]
  (->> (d/q '{:find [?e ?a ?v ?tx ?op]
              :in [?log ?txid]
              :where [[(tx-data ?log ?txid) [[?e ?a ?v ?tx ?op]]]]}
            (d/log (pcd/conn)) (:db/id transaction))
    (map ->datom)
    set))

(defn get-document-tx-ids
  "Returns array of tx-ids sorted by db/txInstant"
  [db doc]
  (map first
       (sort-by second
                (d/q '{:find [?t ?tx]
                       :in [$ ?doc-id]
                       :where [[?t :transaction/document ?doc-id]
                               [?t :transaction/broadcast]
                               [?t :db/txInstant ?tx]]}
                     db (:db/id doc)))))

(defn reproduce-transaction [db tx-id]
  (let [tx (d/entity db tx-id)]
    {:tx-data (tx-data tx)
     :tx tx
     :db-after db}))

(defn get-document-transactions
  "Returns a lazy sequence of transactions for a document in order of db/txInstant."
  [db doc]
  (map (partial reproduce-transaction db)
       (get-document-tx-ids db doc)))

(defn replace-frontend-ids [db doc-id txes]
  (let [a (d/entid db :frontend/id)]
    (map (fn [tx]
           (if (= (:a tx) a)
             (assoc tx
                    :v (UUID. doc-id (web-peer/client-part (:v tx))))
             tx))
         txes)))

(defn doc-id-attr->ref-attr [db {:keys [e v] :as d}]
  (let [ent (d/entity db e)]
    (cond (:layer/type ent) :layer/document
          (:chat/body ent)  :chat/document
          (:layer/name ent) :layer/document
          (= :layer (:entity/type ent)) :layer/document
          (:layer/end-x ent) :layer/document
          :else (throw (Exception. (format "couldn't find attr for %s" d))))))

(defn replace-document-ids [db txes]
  (let [a (d/entid db :document/id)]
    (map (fn [tx]
           (if (= (:a tx) a)
             (assoc tx
                    :a (doc-id-attr->ref-attr (d/as-of db (if (:added tx)
                                                            (:tx tx)
                                                            (dec (:tx tx))))
                                              tx))
             tx))
         txes)))

(defn copy-transactions [db doc new-doc & {:keys [sleep-ms]
                                           :or {sleep-ms 1000}}]
  (let [conn (pcd/conn)
        txes (->> (get-document-transactions db doc)
               (map (fn [tx]
                      (update-in tx
                                 [:tx-data]
                                 (fn [tx-data]
                                   (->> tx-data
                                     (remove #(= (:e %) (:db/id (:tx tx))))
                                     (map #(if (= (:v %) (:db/id doc))
                                             (assoc % :v (:db/id new-doc))
                                             %))
                                     (replace-frontend-ids db (:db/id new-doc))
                                     (replace-document-ids db)))))))
        eid-translations (-> (reduce (fn [acc {:keys [tx-data]}]
                                       (set/union acc (set (map :e tx-data))))
                                     #{} txes)
                           (disj (:db/id doc))
                           (zipmap (repeatedly #(d/tempid :db.part/user)))
                           (assoc (:db/id doc) (:db/id new-doc)))
        reverse-eid-translations (set/map-invert eid-translations)
        new-eids (reduce (fn [new-eid-trans tx]
                           (if-not (seq (:tx-data tx))
                             new-eid-trans
                             (let [txid (d/tempid :db.part/tx)
                                   tempids (map #(get eid-translations %)
                                                (remove #(get new-eid-trans %)
                                                        (map :e (:tx-data tx))))
                                   new-tx @(d/transact conn (conj (map #(-> %
                                                                          (update-in [:e] (fn [e] (or (get new-eid-trans e)
                                                                                                      (get eid-translations e))))
                                                                          pcd/datom->transaction)
                                                                       (:tx-data tx))
                                                                  (merge
                                                                   {:db/id txid
                                                                    :transaction/document (:db/id new-doc)}
                                                                   (when (:transaction/broadcast (:tx tx))
                                                                     {:transaction/broadcast true}))))]
                               (merge new-eid-trans
                                      (zipmap (map #(get reverse-eid-translations %) tempids)
                                              (map #(d/resolve-tempid (:db-after new-tx) (:tempids new-tx) %) tempids))))))
                         {} txes)]
    @(d/transact conn (map (fn [e]
                             [:db/add e :frontend/id (UUID. (:db/id new-doc) e)])
                           (remove #(:frontend/id (d/entity db %))
                                   (vals new-eids))))
    new-doc))
