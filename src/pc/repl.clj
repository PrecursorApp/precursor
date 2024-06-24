(ns pc.repl
  "Utility functions to make repl access more convenient.
   Also serves as a guide for how nses should be aliased"
  (:require [cemerick.url :as url]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clj-time.core :as time]
            [clojure.core.async :as async]
            [clojure.java.javadoc :refer (javadoc)]
            [clojure.repl :refer :all]
            [datomic.api :as d]
            [pc.billing :as billing]
            [pc.datomic :as pcd]
            [pc.datomic.web-peer :as web-peer]
            [pc.email :as email]
            [pc.http.plan :as plan-http]
            [pc.http.sente :as sente]
            [pc.models.chat :as chat-model]
            [pc.models.cust :as cust-model]
            [pc.models.doc :as doc-model]
            [pc.models.flag :as flag-model]
            [pc.models.issue :as issue-model]
            [pc.models.layer :as layer-model]
            [pc.models.permission :as permission-model]
            [pc.models.plan :as plan-model]
            [pc.models.team :as team-model]
            [pc.stripe :as stripe]
            [slingshot.slingshot :refer (try+ throw+)])
  (:import [java.util UUID]))

(defmacro pomegranate-load [artifact]
  `(do
     (require 'cemerick.pomegranate)
     (cemerick.pomegranate/add-dependencies
      :coordinates '[~artifact]
      :repositories (merge cemerick.pomegranate.aether/maven-central {"clojars" "http://clojars.org/repo"}))))

(defmacro browser-repl []
  `(do
     (require 'weasel.repl.websocket)
     (cemerick.piggieback/cljs-repl :repl-env (weasel.repl.websocket/repl-env :ip "0.0.0.0" :port 9001))))

(defmacro java-doc [x]
  `(do
     (require 'clojure.java.javadoc)
     (clojure.java.javadoc/javadoc ~x)))







































;; Optimistic talk
;; ------------------------------------------------------------------------------

;; clojure

(defn pp-tx-data [tx]
  (let [db (:db-after tx)
        pv (fn [d]
             (if-let [ident (:db/ident (d/entity db (:v d)))]
               ident
               (:v d)))]
    (d/ident (pcd/default-db) (:a (first (:tx-data mytx))))
    (clojure.pprint/pprint
     (mapv (fn [d]
             [(d/ident db (:a d)) (pv d) (if (:added d) :added :removed)])
           (remove #(or (= :frontend/id (d/ident db (:a %)))
                        (:db/txInstant (d/entity db (:e %))))
                   (:tx-data tx))))))

(comment
  ;; 1. fill in document-id
  (do
    (def document-id 17592186047659)
    (def document (doc-model/find-by-id (pcd/default-db) document-id))
    (def frontend-part 1184)
    (def frontend-id (UUID. (:db/id document) frontend-part)))

  ;; 2. Eval in cljs repl
  (defn cljs-pp-tx-data [tx]
    (mapv (fn [d]
            [(:a d) (:v d) (if (:added d) :added :removed)])
          (:tx-data tx)))

  ;; 3. Eval in cljs repl
  (cljs-pp-tx-data (datascript.core/transact! (:db @frontend.core/debug-state)
                                              [{:db/id 1184
                                                :layer/name "placeholder"
                                                :layer/opacity 1.0
                                                :layer/stroke-width 1.0
                                                :layer/stroke-color "black"
                                                :layer/start-x 200.0
                                                :layer/end-x 500.0
                                                :layer/start-y 100.0
                                                :layer/end-y 400.0
                                                :layer/fill "none"
                                                :layer/type :layer.type/rect}]
                                              {:can-undo? true}))

  ;; 4. Eval small change in cljs repl (rect -> line)
  (cljs-pp-tx-data (datascript.core/transact! (:db @frontend.core/debug-state)
                                              [{:db/id 1184
                                                :layer/name "placeholder"
                                                :layer/opacity 1.0
                                                :layer/stroke-width 1.0
                                                :layer/stroke-color "black"
                                                :layer/start-x 200.0
                                                :layer/end-x 500.0
                                                :layer/start-y 100.0
                                                :layer/end-y 400.0
                                                :layer/fill "none"
                                                :layer/type :layer.type/line}]
                                              {:can-undo? true}))

  ;; 5. Eval in clj repl
  (pp-tx-data @(d/transact (pcd/conn) [{:db/id (d/tempid :db.part/user)
                                        :layer/name "placeholder"
                                        :layer/document (:db/id document)
                                        :layer/opacity 1.0
                                        :layer/stroke-width 1.0
                                        :layer/stroke-color "black"
                                        :layer/start-x 200.0
                                        :layer/end-x 400.0
                                        :layer/start-y 100.0
                                        :layer/end-y 300.0
                                        :layer/fill "none"
                                        :layer/type :layer.type/rect
                                        :frontend/id frontend-id}
                                       {:db/id (d/tempid :db.part/tx)
                                        :transaction/broadcast true
                                        :transaction/document (:db/id document)
                                        :session/uuid (d/squuid)
                                        :session/client-id (str (d/squuid))}]))
  )

;; clojurescript
(comment
)
