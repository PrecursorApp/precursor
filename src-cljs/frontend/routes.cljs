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
  (defroute root "/" [{:keys [query-params]}]
    (put! nav-ch [:root {:query-params query-params}]))
  (defroute home "/home" [{:keys [query-params]}]
    (put! nav-ch [:root {:query-params query-params}]))
  (defroute home "/new" [{:keys [query-params]}]
    (put! nav-ch [:new {:query-params query-params}]))
  (defroute document #"/document/(\d+)" [doc-id {:keys [query-params]}]
    (put! nav-ch [:document {:document/id (long doc-id)
                             :query-params query-params}])))

(defn define-routes! [state]
  (let [nav-ch (get-in @state [:comms :nav])]
    (define-user-routes! nav-ch)
    (define-spec-routes! nav-ch)))
