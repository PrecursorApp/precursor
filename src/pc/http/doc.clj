(ns pc.http.doc
  (:require [clojure.tools.reader.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [pc.datomic :as pcd]
            [pc.models.chat-bot :as chat-bot-model]
            [pc.models.doc :as doc-model]
            [pc.models.layer :as layer-model])
  (:import [java.io PushbackReader]))

(defn read-doc [doc]
  (edn/read (PushbackReader. (io/reader (io/resource (format "docs/%s.edn" doc))))))

(defn docs* []
  {"interactive-demo" (read-doc "interactive-demo")})

(def docs (memoize docs*))

(defn duplicate-doc [document-name cust]
  (let [doc (doc-model/create-public-doc! (merge {:document/name (str "Copy of " document-name)
                                                  :document/chat-bot (rand-nth chat-bot-model/chat-bots)}
                                                 (when (:cust/uuid cust) {:document/creator (:cust/uuid cust)})))]
    (if-let [layers (get (docs) document-name)]
      @(d/transact (pcd/conn) (conj (map #(assoc %
                                            :db/id (d/tempid :db.part/user)
                                            :document/id (:db/id doc))
                                         layers)
                                    (merge {:db/id (d/tempid :db.part/tx)
                                            :document/id (:db/id doc)}
                                           (when (:cust/uuid cust)
                                             {:cust/uuid (:cust/uuid cust)}))))
      (log/infof "requested duplicate doc for non-existent doc %s" document-name))
    (:db/id doc)))

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
