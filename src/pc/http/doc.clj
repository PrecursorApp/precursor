(ns pc.http.doc
  (:require [clojure.tools.reader.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [pc.datomic :as pcd]
            [pc.models.doc :as doc-model]
            [pc.models.layer :as layer-model])
  (:import [java.io PushbackReader]))

(defn read-doc [doc]
  (edn/read (PushbackReader. (io/reader (io/resource (format "docs/%s.edn" doc))))))

(def docs
  {"interactive-demo" (read-doc "interactive-demo")})

(defn duplicate-doc [document-name cust]
  (let [conn (pcd/conn)
        [new-doc-id] (pcd/generate-eids conn 1)]
    (if-let [layers (get docs document-name)]
      @(d/transact conn (conj (map #(assoc %
                                      :db/id (d/tempid :db.part/user)
                                      :document/id new-doc-id)
                                   layers)
                              (merge {:db/id (d/tempid :db.part/tx)
                                      :document/id new-doc-id}
                                     (when (:cust/uuid cust)
                                       {:cust/uuid (:cust/uuid cust)}))))
      (log/infof "requested duplicate doc for non-existent doc %s" document-name))
    new-doc-id))

(defn save-doc
  "Helper function to save an existing doc, still needs to be committed and added to docs
   after it has been saved."
  [doc-id doc-name]
  (spit (format "resources/docs/%s.edn" doc-name)
        (pr-str (map #(dissoc % :db/id :document/id)
                     ;; we may want to save groups at some point in the future, right now they
                     ;; just take up space.
                     (remove #(= :layer.type/group (:layer/type %))
                             (layer-model/find-by-document (pcd/default-db) {:db/id doc-id}))))))
