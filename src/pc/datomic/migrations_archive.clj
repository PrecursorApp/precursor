(ns pc.datomic.migrations-archive
  "Where migrations go to die. We keep them here for debugging purposes,
   but we don't want them to run again.")


(defn layer-longs->floats
  "Converts the attributes on the layer that should have been floats, but were mistakenly
   specified as longs."
  [conn]
  #_(let [layer-attrs #{:start-x :start-y :end-x :end-y :stroke-width :opacity
                        :start-sx :start-sy :current-sx :current-sy}
          idents (set (map (fn [a] (keyword "layer" (name a))) layer-attrs))
          layer-schemas (filter #(contains? idents (:db/ident %))
                                (pcd/touch-all '{:find [?t]
                                                 :where [[?t :db/ident]]}
                                               (db conn)))
          ident->float (fn [ident]
                         (keyword "layer" (str (name ident) "-float")))
          ident->long (fn [ident]
                        (keyword "layer" (str (name ident) "-long")))
          temp-schemas (for [s layer-schemas]
                         (-> s
                           (update-in [:db/ident] ident->float)
                           (merge {:db/valueType :db.type/float
                                   :db/id (d/tempid :db.part/db)
                                   :db.install/_attribute :db.part/db})))
          new-schemas (for [s layer-schemas]
                        (merge s {:db/valueType :db.type/float
                                  :db/id (d/tempid :db.part/db)
                                  :db.install/_attribute :db.part/db}))
          db (db conn)]
      (when (seq layer-schemas) ;; don't try to migrate on a fresh db

        ;; create temporary schema with :layer/attr-float for each attr
        @(d/transact conn temp-schemas)

        ;; migrate long properties to the new float properties
        (doseq [ident idents]
          (dorun (map (fn [[e]]
                        (let [new-val (-> (d/entity db e) (get ident) float)]
                          @(d/transact conn [[:db/add e (ident->float ident) new-val]])))
                      (q '{:find [?t] :in [$ ?ident]
                           :where [[?t ?ident]]}
                         db ident))))

        ;; rename the long attrs to new name
        @(d/transact conn (for [ident idents]
                            {:db/id ident
                             :db/ident (ident->long ident)}))

        ;; rename the float attrs to normal attrs
        @(d/transact conn (for [ident idents]
                            {:db/id (ident->float ident)
                             :db/ident ident})))))

(defn layer-child-uuid->long
  "Converts the child type from uuids to long"
  [conn]
  ;; don't try to migrate on a fresh db
  #_(when (pcd/touch-one '{:find [?t]
                           :where [[?t :db/ident :layer/child]
                                   [?t :db/valueType :db.type/uuid]]}
                         (db conn))
      ;; rename the uuid attr to a new name
      @(d/transact conn [{:db/id :layer/child
                          :db/ident :layer/child-deprecated}])))

(defn make-existing-documents-public
  "Marks all existing documents as public"
  [conn]
  #_(let [db (db conn)
        doc-ids (map first (d/q '{:find [?t]
                                  :where [[?t :document/name]]}
                                db))]
    (dorun (map-indexed
            (fn [i ids]
              (log/infof "making batch %s of %s public" i (/ (count doc-ids) 1000 1.0))
              @(d/transact conn (map (fn [id]
                                       {:db/id id
                                        :document/privacy :document.privacy/public})
                                     ids)))
            (partition-all 1000 doc-ids)))))

;; migrate fake documents migration
(defn change-document-id [conn db old-id new-id]
  #_(let [eids (map first (d/q '{:find [?t]
                                 :in [$ ?old-id]
                                 :where [[?t :document/id ?old-id]]}
                               db old-id))]
      (log/infof "migrating %s eids from %s to %s" (count eids) old-id new-id)
      @(d/transact conn (mapv (fn [eid] {:db/id eid :document/id new-id}) eids))))

(defn migrate-fake-documents
  "Migrates documents (i.e. chats, layers, transactions) with invalid document ids to valid document ids"
  [conn]
  #_(let [db (db conn)
          doc-ids (map first (d/q '{:find [?doc-id]
                                    :in [$]
                                    :where [[?t :document/id ?doc-id]
                                            [(missing? $ ?doc-id :document/name)]]}
                                  db))]
      (dorun
       (map-indexed (fn [i invalid-id]
                      (let [new-doc (doc-model/create-public-doc! {:document/invalid-id invalid-id})]
                        (log/infof "migrating %s (%s of %s)" invalid-id i (count doc-ids))
                        (change-document-id conn db invalid-id (:db/id new-doc))))
                    doc-ids))))


;; bounding boxes migration
(defn fix-text-bounding-boxes [db conn]
  #_(let [tx-count (atom 0)]
      (doseq [e (map first (d/q '[:find ?t
                                  :where [?t :layer/type :layer.type/text]]
                                db))
              :let [layer (d/entity db e)]
              ;; These have the right height, so we'll assume they're already correct
              :when (not= -23.5 (- (:layer/end-y layer) (:layer/start-y layer)))]
        (let [new-end-x (+ (:layer/start-x layer) (* 11 (count (:layer/text layer))))
              new-end-y (+ (:layer/start-y layer) -23.5)]
          (try+
           @(d/transact conn [[:db.fn/cas e :layer/end-x (:layer/end-x layer) new-end-x]
                              [:db.fn/cas e :layer/end-y (:layer/end-y layer) new-end-y]])
           (swap! tx-count inc)
           (when (zero? (mod @tx-count 10000))
             (log/infof "sleeping for 30 seconds to give the transactor a rest (%s transactions)" @tx-count)
             (Thread/sleep (* 30 1000)))
           (catch Object t
             (if (pcd/cas-failed? t)
               (let [msg (format "cas failed for text layer with id %s" e)]
                 (println msg)
                 (log/error msg))
               (throw+))))))))

(defn parse-points [path]
  #_(partition-all 2 (map #(Float/parseFloat %) (re-seq #"[\d\.]+" path))))

(defn fix-pen-bounding-boxes [db conn]
  #_(let [tx-count (atom 0)]
      (doseq [e (map first (d/q '[:find ?t
                                  :where [?t :layer/type :layer.type/path]]
                                db))
              :let [layer (d/entity db e)
                    points (some-> layer :layer/path parse-points)]
              :when (seq points)]
        (let [new-start-x (apply min (map first points))
              new-end-x (apply max (map first points))
              new-start-y (apply min (map second points))
              new-end-y (apply max (map last points))]
          (when-not (and (= new-start-x (:layer/start-x layer))
                         (= new-end-x (:layer/end-x layer))
                         (= new-start-y (:layer/start-y layer))
                         (= new-end-y (:layer/end-y layer)))
            (try+
             @(d/transact conn [[:db.fn/cas e :layer/start-x (:layer/start-x layer) new-start-x]
                                [:db.fn/cas e :layer/end-x (:layer/end-x layer) new-end-x]
                                [:db.fn/cas e :layer/start-y (:layer/start-y layer) new-start-y]
                                [:db.fn/cas e :layer/end-y (:layer/end-y layer) new-end-y]])
             (swap! tx-count inc)
             (when (zero? (mod @tx-count 10000))
               (log/infof "sleeping for 30 seconds to give the transactor a rest (%s transactions)" @tx-count)
               (Thread/sleep (* 30 1000)))
             (catch Object t
               (if (pcd/cas-failed? t)
                 (let [msg (format "cas failed for pen layer with id %s" e)]
                   (println msg)
                   (log/error msg))
                 (throw+)))))))))

(defn fix-bounding-boxes
  "Fixes bounding boxes for text and pen drawings"
  [conn]
  #_(time
     (let [db (db conn)]
       (log/info "fixing text bounding boxes")
       (fix-text-bounding-boxes db conn)
       (log/info "fixing pen bounding boxes")
       (fix-pen-bounding-boxes db conn))))
