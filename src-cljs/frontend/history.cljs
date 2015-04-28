(ns frontend.history
  (:require [clojure.string :as string]
            [frontend.utils :as utils]
            [goog.events :as events]
            [goog.history.Html5History :as html5-history]
            [goog.window :as window]
            [secretary.core :as sec])
  (:import [goog.history Html5History]
           [goog.events EventType Event BrowserEvent]
           [goog History]))


;; see this.transformer_ at http://goo.gl/ZHLdwa
(def ^{:doc "Custom token transformer that preserves hashes"}
  token-transformer
  (let [transformer (js/Object.)]
    (set! (.-retrieveToken transformer)
          (fn [path-prefix location]
            (str (subs (.-pathname location) (count path-prefix))
                 (when-let [query (.-search location)]
                   query)
                 (when-let [hash (second (string/split (.-href location) #"#"))]
                   (str "#" hash)))))

    (set! (.-createUrl transformer)
          (fn [token path-prefix location]
            (str path-prefix token)))

    transformer))

(defn current-token [history-imp]
  (.-_current_token history-imp))

(defn set-current-token!
  "Lets us keep track of the history state, so that we don't dispatch twice on the same URL"
  [history-imp & [token]]
  (set! (.-_current_token history-imp) (or token (.getToken history-imp))))

(defn setup-dispatcher! [history-imp]
  (events/listen history-imp goog.history.EventType.NAVIGATE
                 #(let [token-before (current-token history-imp)
                        token-after (set-current-token! history-imp (.-token %))]
                    (when-not (= (re-find #"^[^\?]+" token-before)
                                 (re-find #"^[^\?]+" token-after))
                      (sec/dispatch! (str "/" token-after))))))

(defn bootstrap-dispatcher!
  "We need lots of control over when we start listening to navigation events because
   we may want to ignore the first event if the server sends an error status code (e.g. 401)
   This function lets us ignore the first event that history-imp fires when we enable it. We'll
   manually dispatch if there is no error code from the server."
  [history-imp]
  (events/listenOnce history-imp goog.history.EventType.NAVIGATE #(setup-dispatcher! history-imp)))

(defn disable-erroneous-popstate!
  "Stops the browser's popstate from triggering NAVIGATION events unless the url has really
   changed. Fixes duplicate dispatch in Safari and the build machines."
  [history-imp]
  ;; get this history instance's version of window, might make for easier testing later
  (let [window (.-window_ history-imp)]
    (events/removeAll window goog.events.EventType.POPSTATE)
    (events/listen window goog.events.EventType.POPSTATE
                   #(if (= (.getToken history-imp)
                           (.-_current_token history-imp))
                      (utils/mlog "Ignoring duplicate dispatch event to" (.getToken history-imp))
                      (.onHistoryEvent_ history-imp)))))

(defn path-matches?
  "True if the two tokens are the same except for the fragment"
  [token-a token-b]
  (= (first (string/split token-a #"#"))
     (first (string/split token-b #"#"))))

(defn new-window-click? [event]
  (or (.isButton event goog.events.BrowserEvent.MouseButton.MIDDLE)
      (and (.-platformModifierKey event)
           (.isButton event goog.events.BrowserEvent.MouseButton.LEFT))))

(defn setup-link-dispatcher! [history-imp tokens-to-ignore]
  (let [dom-helper (goog.dom.DomHelper.)
        ignore-pattern (re-pattern (str "^" (string/join "|^" tokens-to-ignore)))]
    (events/listen js/document "click"
                   #(let [-target (.. % -target)
                          target (if (= (.-tagName -target) "A")
                                   -target
                                   (.getAncestorByTagNameAndClass dom-helper -target "A"))
                          location (when target (str (.-pathname target) (.-search target) (.-hash target)))
                          new-token (when (seq location) (subs location 1))]
                      (when (and (seq location)
                                 (= (.. js/window -location -hostname)
                                    (.-hostname target))
                                 (not (or (new-window-click? %)
                                          (contains? #{"_blank" "_self"} (.-target target)))))
                        (when-not (re-find ignore-pattern new-token)
                          (.stopPropagation %)
                          (.preventDefault %))

                        (utils/mlog "navigating to" location)
                        (.setToken history-imp new-token)
                        ;; This might not be the best place for this--too much separation
                        ;; between history and routing
                        (when (= "_top" (.-target target))
                          (set! js/document.body.scrollTop 0)))))))

(defn new-history-imp [tokens-to-ignore]
  ;; need a history element, or goog will overwrite the entire dom
  (let [dom-helper (goog.dom.DomHelper.)
        node (.createDom dom-helper "input" #js {:class "history hide"})]
    (.append dom-helper node))
  (doto (goog.history.Html5History. js/window token-transformer)
    (.setUseFragment false)
    (.setPathPrefix "/")
    (bootstrap-dispatcher!)
    (set-current-token!) ; Stop Safari from double-dispatching
    (disable-erroneous-popstate!) ; Stop Safari from double-dispatching
    (.setEnabled true) ; This will fire a navigate event with the current token
    (setup-link-dispatcher! tokens-to-ignore)))
