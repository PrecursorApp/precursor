(ns pc.replay
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [fs]
            [gift.core :refer [make-gif]]
            [pc.datomic :as pcd]
            [pc.datomic.schema :as schema]
            [pc.models.layer :as layer-model]
            [pc.render :as render]
            [pc.utils :as utils]))
            [pc.datomic.web-peer :as web-peer])
  (:import java.util.UUID))

(defn get-document-transactions
  "Gets the broadcasted transactions for a document"
  [db doc]
  (map #(d/entity db (first %))
       (d/q '{:find [?t]
              :in [$ ?doc-id]
              :where [[?t :document/id ?doc-id]
                      [?t :db/txInstant]]}
            db (:db/id doc))))

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

(defn replace-frontend-ids [db doc-id txes]
  (let [a (d/entid db :frontend/id)]
    (map (fn [tx]
           (if (= (:a tx) a)
             (assoc tx
                    :v (UUID. doc-id (web-peer/client-part (:v tx)))
                    :a (d/entid (pcd/default-db) :frontend/id))
             tx))
         txes)))

(defn copy-transactions [db doc new-doc & {:keys [sleep-ms]
                                           :or {sleep-ms 1000}}]
  (let [conn (pcd/conn)
        tx-datas (->> (get-document-transactions db doc)
                   (sort-by :db/txInstant)
                   (map (fn [t]
                          (->> (tx-data t)
                            (remove #(= (:e %) (:db/id t)))
                            (map #(if (= (:v %) (:db/id doc))
                                    (assoc % :v (:db/id new-doc))
                                    %))
                            (replace-frontend-ids db (:db/id new-doc))))))
        eid-translations (-> (apply concat (map #(map :e %) tx-datas))
                           set
                           (disj (:db/id doc))
                           (zipmap (repeatedly #(first (pcd/generate-eids conn 1))))
                           (assoc (:db/id doc) (:db/id new-doc)))]
    (doseq [tx-data tx-datas]
      (def my-tx-data tx-data)
      (let [txid (d/tempid :db.part/tx)]
        @(d/transact conn (conj (map #(-> %
                                        (update-in [:e] eid-translations)
                                        pcd/datom->transaction)
                                     tx-data)
                                {:db/id txid
                                 :document/id (:db/id new-doc)
                                 :transaction/broadcast true}))
        (Thread/sleep sleep-ms)))))

(defn ->gif [db doc]
  (let [image-dir (fs/tempdir)]
    (try
      (let [transactions (get-document-transactions db doc)
            files (for [t (sort-by :db/txInstant transactions)
                        :let [t-db (d/as-of db (:db/id t))
                              filepath (fs/join image-dir (str (.toEpochMilli (.toInstant (:db/txInstant t)))
                                                               ".jpg"))
                              layers (layer-model/find-by-document t-db doc)
                              svg (render/render-layers layers :invert-colors? true)
                              image-stream (render/svg->jpg svg)]]
                    (do
                      (io/copy image-stream (io/file filepath))
                      filepath))]
        (make-gif "output.gif" files))
      (finally
        ;; XXX delete image-dir
        (utils/inspect image-dir)))))
