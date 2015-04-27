(ns pc.models.cust
  "cust is what would usually be called user, we call it cust b/c
   Clojure has already taken the name user in the repl"
  (:require [clojure.set :as set]
            [clojure.tools.logging :as log]
            [datomic.api :refer [db q] :as d]
            [pc.datomic :as pcd]
            [pc.datomic.schema :as schema]
            [pc.models.flag :as flag-model]))


;; We'll pretend we have a type here
#_(t/def-alias Cust (HMap :mandatory {:cust/email String
                                      :db/id Long
                                      ;; probably a uuid type
                                      :cust/uuid String
                                      :google-account/sub String}
                          :optional {:cust/name String
                                     ;; probably a uuid type
                                     :cust/http-sesion-key String}))

(def admin-emails #{"dwwoelfel@gmail.com" "danny.newidea@gmail.com"})

(defn all [db]
  (d/q '{:find [[?e ...]]
         :where [[?e :google-account/sub]]}
       db))

(defn find-by-id [db id]
  (let [candidate (d/entity db id)]
    ;; faster than using a datalog query
    (when (:cust/email candidate)
      candidate)))

;; TODO: maybe these should return an entity instead of touching?
(defn find-by-google-sub [db google-sub]
  (d/entity db (ffirst (d/q '{:find [?e] :in [$ ?sub]
                              :where [[?e :google-account/sub ?sub]]}
                            db google-sub))))

(defn find-by-email [db email]
  (d/entity db (ffirst (d/q '{:find [?e] :in [$ ?email]
                              :where [[?e :cust/email ?email]]}
                            db email))))

(defn find-by-uuid [db uuid]
  (d/entity db (ffirst (d/q '{:find [?e] :in [$ ?uuid]
                              :where [[?e :cust/uuid ?uuid]
                                      [?e :cust/email]]}
                            db uuid))))


(defn find-by-http-session-key [db http-session-key]
  (d/entity db (ffirst (d/q '{:find [?e] :in [$ ?key]
                              :where [[?e :cust/http-session-key ?key]]}
                            db http-session-key))))

(defn retract-session-key! [cust]
  @(d/transact (pcd/conn) [[:db/retract (:db/id cust) :cust/http-session-key (:cust/http-session-key cust)]]))

(defn create! [cust-attrs]
  (let [temp-id (d/tempid :db.part/user)
        {:keys [tempids db-after]} @(d/transact (pcd/conn)
                                                [(assoc cust-attrs :db/id temp-id)])]
    (->> (d/resolve-tempid db-after
                           tempids
                           temp-id)
         (d/entity db-after))))

(defn update! [cust new-attrs]
  (let [{:keys [db-after]} @(d/transact (pcd/conn) (map (fn [[a v]]
                                                          [:db/add (:db/id cust) a v])
                                                        new-attrs))]
    (d/entity db-after (:db/id cust))))

(defn cust-count [db]
  (ffirst (q '{:find [(count ?t)]
               :where [[?t :google-account/sub]]}
             db)))

(defn created-at [db cust]
  (ffirst (d/q '{:find [?i]
                 :in [$ ?sub]
                 :where [[_ :google-account/sub ?sub ?tx]
                         [?tx :db/txInstant ?i]]}
               db (:google-account/sub cust))))

(defn turn-on-private-docs [db email]
  (let [cust (find-by-email db email)]
    (flag-model/add-flag cust :flags/private-docs)))

(defn read-api [cust]
  (select-keys cust
               (concat
                (schema/browser-setting-idents)
                [:cust/email :cust/uuid :cust/name :flags])))

(defn public-read-api [cust]
  (select-keys cust [:cust/uuid :cust/name :cust/color-name]))

(defn public-read-api-per-uuids
  "Returns hashmap of uuid to cust-map, e.g. {#uuid '123' {:cust/uuid '123' :cust/name 'd'}}"
  [db uuids]
  ;; TODO: what should your team know about you?
  (let [uuids (into #{} uuids)
        cust-ids (d/q '{:find [[?t ...]]
                        :in [$ ?uuids]
                        :where [[?t :cust/email]
                                [?t :cust/uuid ?uuid]
                                [(contains? ?uuids ?uuid)]]}
                      db uuids)]
    (reduce (fn [acc cust-id]
              (let [cust (d/entity db cust-id)]
                (assoc acc (:cust/uuid cust) (select-keys cust [:cust/uuid :cust/name :cust/color-name])))) {} cust-ids)))

(defn cust-uuids-for-doc [db doc-id]
  (d/q '{:find [[?uuid ...]]
         :in [$ ?doc-id]
         :where [[?t :transaction/document ?doc-id]
                 [?t :cust/uuid ?uuid]]}
       db doc-id))

(defn choose-color
  "Finds a color for the given uuid (either a cust/uuid or a session/uuid),
   tries to pick a color that won't conflict with any of his collaborators."
  [db uuid]
  (let [doc-ids (d/q '{:find [[?doc-id ...]]
                       :in [$ ?uuid]
                       :where [(or [?t :cust/uuid ?uuid]
                                   [?t :session/uuid ?uuid])
                               [?t :transaction/document ?doc-id]]}
                     db uuid)
        cust-uuids (mapcat #(d/q '{:find [[?cust-uuid ...]]
                                   :in [$ ?doc-id]
                                   :where [[?t :transaction/document ?doc-id]
                                           [?t :cust/uuid ?cust-uuid]]}
                                 db %)
                           doc-ids)
        collab-colors (remove nil? (map #(d/q '{:find [?color .]
                                                :in [$ ?uuid]
                                                :where [[?t :cust/color-name ?color]
                                                        [?t :cust/uuid ?uuid]]}
                                              db %)
                                        (disj (set cust-uuids) uuid)))
        all-colors (schema/color-enums)]
    (or (some-> (set/difference all-colors (set collab-colors))
          seq
          rand-nth)
        ;; get the least-frequent color if they're all taken
        (ffirst (sort-by last (frequencies collab-colors))))))

(def prcrsr-bot-email "prcrsr-bot@prcrsr.com")

(defn prcrsr-bot [db]
  (find-by-email db prcrsr-bot-email))
