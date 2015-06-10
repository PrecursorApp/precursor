(ns frontend.routes
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.string :as str]
            [frontend.async :refer [put!]]
            [frontend.utils :as utils :include-macros true]
            [frontend.urls :as urls]
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

  (defroute designer-news "/designer-news" {:keys [query-params]}
    (put! nav-ch [:landing {:query-params query-params
                            :utm-campaign "designer-news"}]))

  (defroute pricing "/pricing" {:keys [query-params]}
    (put! nav-ch [:pricing {:query-params query-params}]))

  (defroute team-features "/features/team" {:keys [query-params]}
    (put! nav-ch [:team-features {:query-params query-params}]))

  (defroute trial "/trial" {:keys [type query-params]}
    (put! nav-ch [:trial {:query-params query-params
                          :trial-type type}]))


  (defroute new-doc "/new" {:keys [query-params]}
    (put! nav-ch [:new {:query-params query-params}]))

  (defroute issue-list "/issues" {:keys [query-params]}
    (put! nav-ch [:issues-list {:query-params query-params}]))

  (defroute issue-search "/issues/search" {:keys [query-params]}
    (put! nav-ch [:issues-search {:query-params query-params}]))

  (defroute issue-complete "/issues/completed" {:keys [query-params]}
    (put! nav-ch [:issues-completed {:query-params query-params}]))

  (defroute issue "/issues/:issue-uuid" {:keys [query-params issue-uuid]}
    (put! nav-ch [:single-issue {:query-params query-params
                                 :issue-uuid issue-uuid}]))

  (defroute document #"/document/[A-Za-z0-9_-]*?-{0,1}(\d+)$" [doc-id args]
    (let [params (:query-params args)]
      (if-let [overlay (:overlay params)]
        (put! nav-ch [:navigate! {:path (urls/overlay-path {:db/id doc-id} overlay
                                                           :query-params (dissoc params :overlay))
                                  :replace-token? true}])
        (put! nav-ch [:document {:document/id (long doc-id)
                                 :query-params params}]))))

  (defroute plan-submenu #"/document/[A-Za-z0-9_-]*?-{0,1}(\d+)/plan/([\w-]+)$" [doc-id submenu args]
    (put! nav-ch [:plan-submenu {:document/id (long doc-id)
                                 :submenu submenu
                                 :query-params (:query-params args)}]))

  (defroute overlay #"/document/[A-Za-z0-9_-]*?-{0,1}(\d+)/([\w-]+)$" [doc-id overlay args]
    (put! nav-ch [:overlay {:document/id (long doc-id)
                            :overlay overlay
                            :query-params (:query-params args)}])))

(defn define-routes! [state]
  (let [nav-ch (get-in @state [:comms :nav])]
    (define-user-routes! nav-ch)
    (define-spec-routes! nav-ch)))
