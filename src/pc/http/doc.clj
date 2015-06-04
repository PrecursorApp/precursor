(ns pc.http.doc
  (:require [clj-time.core :as time]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.tools.reader.edn :as edn]
            [datomic.api :as d]
            [pc.datomic :as pcd]
            [pc.datomic.web-peer :as web-peer]
            [pc.models.chat-bot :as chat-bot-model]
            [pc.models.doc :as doc-model]
            [pc.models.layer :as layer-model]
            [pc.util.md5 :as md5]
            [pc.utils :as utils])
  (:import java.io.PushbackReader
           java.util.UUID))

(defn read-doc [doc]
  (edn/read (PushbackReader. (io/reader (io/resource (format "docs/%s.edn" doc))))))

(defn docs* []
  {"interactive-demo" (read-doc "interactive-demo")
   "intro-doc" (read-doc "intro-doc")})

(def docs (memoize docs*))

(defn duplicate-doc [document-name cust]
  (let [doc (doc-model/create-public-doc! (merge {:document/name (str "Copy of " document-name)
                                                  :document/chat-bot (rand-nth chat-bot-model/chat-bots)}
                                                 (when (:cust/uuid cust) {:document/creator (:cust/uuid cust)})))]
    (if-let [layers (get (docs) document-name)]
      @(d/transact (pcd/conn) (conj (map-indexed
                                     (fn [i l]
                                       (let [temp-id (d/tempid :db.part/user)]
                                         (assoc l
                                                :db/id temp-id
                                                :layer/document (:db/id doc)
                                                :frontend/id (UUID. (:db/id doc) (inc i)))))
                                     layers)
                                    (merge {:db/id (d/tempid :db.part/tx)
                                            :transaction/document (:db/id doc)}
                                           (when (:cust/uuid cust)
                                             {:cust/uuid (:cust/uuid cust)}))))
      (log/infof "requested duplicate doc for non-existent doc %s" document-name))
    (:db/id doc)))

(defn translate-layer [layer doc id->tempid]
  (-> layer
    (assoc :layer/document (:db/id doc))
    (update-in [:db/id] id->tempid)
    (update-in [:frontend/id] #(UUID. (:db/id doc) (web-peer/client-part %)))
    (utils/update-when-in [:layer/points-to] #(set (map (comp id->tempid :db/id) %)))
    (utils/update-when-in [:layer/text] #(str/replace % #"danny" (:chat-bot/name (:document/chat-bot doc))))))

(defn maybe-remove-chat-invite [layers]
  ;; Only show chat invite between 9am and 11pm PT
  (if (< 9 (-> (time/now)
             (time/to-time-zone (time/time-zone-for-id "America/Los_Angeles"))
             (time/hour))
         24)
    layers
    (remove #(some->> % :layer/text (re-find #"danny")) layers)))

(defn add-intro-layers [doc]
  (let [doc-id (:db/id doc)
        layers (maybe-remove-chat-invite (get (docs) "intro-doc"))
        id->tempid (reduce (fn [acc layer]
                             (assoc acc (:db/id layer) (d/tempid :db.part/user)))
                           {} layers)]
    @(d/transact (pcd/conn) (conj (map #(translate-layer % doc id->tempid) layers)
                                  {:db/id (d/tempid :db.part/tx)
                                   :transaction/broadcast true
                                   :transaction/document doc-id}))))

(defn save-doc
  "Helper function to save an existing doc, still needs to be committed and added to docs
   after it has been saved."
  [doc-id doc-name]
  (spit (format "resources/docs/%s.edn" doc-name)
        (pr-str (map #(dissoc (pcd/touch+ %) :layer/document)
                     ;; we may want to save groups at some point in the future, right now they
                     ;; just take up space.
                     (remove #(= :layer.type/group (:layer/type %))
                             (layer-model/find-by-document (pcd/default-db) {:db/id doc-id}))))))

(defn last-modified-instant [db doc]
  (ffirst (d/q '[:find (max ?i)
                 :in $ ?doc-id
                 :where
                 [?t :transaction/document ?doc-id]
                 [?t :db/txInstant ?i]]
               db (:db/id doc))))
