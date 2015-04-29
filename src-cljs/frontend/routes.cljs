(ns frontend.routes
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.string :as str]
            [frontend.async :refer [put!]]
            [frontend.utils :as utils :include-macros true]
            [goog.events :as events]
            [secretary.core :as secretary :include-macros true :refer-macros [defroute]])
  (:require-macros [cljs.core.async.macros :as am :refer [go go-loop alt!]]))

(defn define-spec-routes! [nav-ch]
  (defroute trailing-slash #"(.+)/$" [path]
    (put! nav-ch [:navigate! {:path path :replace-token? true}]))
  (defroute not-found "*" []
    (put! nav-ch [:error {:status 404}])))

(defn define-user-routes! [nav-ch]
  (defroute root "/" {:keys [query-params]}
    (put! nav-ch [:landing {:query-params query-params}]))

  (defroute home "/home" {:keys [query-params]}
    (put! nav-ch [:landing {:query-params query-params}]))

  (defroute product-hunt "/product-hunt" {:keys [query-params]}
    (put! nav-ch [:landing {:query-params query-params
                            :utm-campaign "product-hunt"}]))

  (defroute pricing "/pricing" {:keys [query-params]}
    (put! nav-ch [:pricing {:query-params query-params}]))

  (defroute team-features "/features/team" {:keys [query-params]}
    (put! nav-ch [:team-features {:query-params query-params}]))

  (defroute trial "/trial" {:keys [type query-params]}
    (put! nav-ch [:trial {:query-params query-params
                          :trial-type type}]))


  (defroute new-doc "/new" {:keys [query-params]}
    (put! nav-ch [:new {:query-params query-params}]))

  (defroute document #"/document/(\d+)" [doc-id args]
    (put! nav-ch [:document {:document/id (long doc-id)
                             :query-params (:query-params args)}])))

(defn define-routes! [state]
  (let [nav-ch (get-in @state [:comms :nav])]
    (define-user-routes! nav-ch)
    (define-spec-routes! nav-ch)))
