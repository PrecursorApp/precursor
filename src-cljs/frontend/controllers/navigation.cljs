(ns frontend.controllers.navigation
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.string :as str]
            [frontend.async :refer [put!]]
            [frontend.state :as state]
            [frontend.stefon :as stefon]
            [frontend.utils.ajax :as ajax]
            [frontend.utils.state :as state-utils]
            [frontend.utils.vcs-url :as vcs-url]
            [frontend.utils :as utils :refer [mlog merror]]
            [goog.dom]
            [goog.string :as gstring]
            [goog.style])
  (:require-macros [frontend.utils :refer [inspect]]
                   [dommy.macros :refer [sel sel1]]
                   [cljs.core.async.macros :as am :refer [go go-loop alt!]]))

;; TODO we could really use some middleware here, so that we don't forget to
;;      assoc things in state on every handler
;;      We could also use a declarative way to specify each page.

;; --- Helper Methods ---

(defn set-page-title! [& [title]]
  (set! (.-title js/document) (utils/strip-html
                               (if title
                                 (str title  " - CircleCI")
                                 "CircleCI"))))

(defn scroll-to-fragment!
  "Scrolls to the element with id of fragment, if one exists"
  [fragment]
  (when-let [node (goog.dom.getElement fragment)]
    (let [body (sel1 "body")
          node-top (goog.style/getPageOffsetTop node)
          body-top (goog.style/getPageOffsetTop body)]
      (set! (.-scrollTop body) (- node-top body-top)))))

(defn scroll!
  "Scrolls to fragment if the url had one, or scrolls to the top of the page"
  [args]
  (if (:_fragment args)
    ;; give the page time to render
    (utils/rAF #(scroll-to-fragment! (:_fragment args)))
    (utils/rAF #(set! (.-scrollTop (sel1 "body")) 0))))

;; --- Navigation Multimethod Declarations ---

(defmulti navigated-to
  (fn [history-imp navigation-point args state] navigation-point))

(defmulti post-navigated-to!
  (fn [history-imp navigation-point args previous-state current-state]
    (put! (get-in current-state [:comms :ws]) [:unsubscribe-stale-channels])
    navigation-point))

;; --- Navigation Multimethod Implementations ---

(defn navigated-default [navigation-point args state]
  (-> state
      state-utils/clear-page-state
      (assoc :navigation-point navigation-point
             :navigation-data args)))

(defmethod navigated-to :default
  [history-imp navigation-point args state]
  (navigated-default navigation-point args state))

(defn post-default [navigation-point args]
  (set-page-title! (or (:_title args)
                       (str/capitalize (name navigation-point))))
  (scroll! args))

(defmethod post-navigated-to! :default
  [history-imp navigation-point args previous-state current-state]
  (post-default navigation-point args))

(defmethod navigated-to :navigate!
  [history-imp navigation-point args state]
  state)

(defmethod post-navigated-to! :navigate!
  [history-imp navigation-point {:keys [path replace-token?]} previous-state current-state]
  (let [path (if (= \/ (first path))
               (subs path 1)
               path)]
    (if replace-token? ;; Don't break the back button if we want to redirect someone
      (.replaceToken history-imp path)
      (.setToken history-imp path))))
