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
            [pc.models.layer :as layer-model]
            [pc.models.permission :as permission-model]
            [pc.models.plan :as plan-model]
            [pc.models.team :as team-model]
            [pc.stripe :as stripe]
            [slingshot.slingshot :refer (try+ throw+)]))

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
