(ns frontend.utils
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.string :as string]
            [datascript.core :as d]
            [frontend.async :refer [put!]]
            [om.core :as om :include-macros true]
            [cljs-time.core :as time]
            [goog.async.AnimationDelay]
            [goog.crypt :as crypt]
            [goog.crypt.Md5 :as md5]
            [goog.dom]
            [goog.style]
            [goog.Uri]
            [goog.events :as ge]
            [goog.net.EventType :as gevt]
            [sablono.core :as html :include-macros true]
            [frontend.utils.seq :as seq-util]
            [goog.labs.userAgent.browser :as ua-browser])
  (:require-macros [frontend.utils :refer (inspect timing defrender go+)]
                   [cljs.core.async.macros :refer (go)])
  (:import [goog.format EmailAddress]))

(defn csrf-token []
  (or (aget js/window "CSRFToken") ;; remove when backend deploys
      (aget js/window "Precursor" "CSRFToken")))

(def parsed-uri
  (goog.Uri. (-> (.-location js/window) (.-href))))

(defn parse-uri-bool
  "Parses a boolean from a url into true, false, or nil"
  [string]
  (condp = string
    "true" true
    "false" false
    nil))

(def initial-query-map
  {:restore-state? (parse-uri-bool (.getParameterValue parsed-uri "restore-state"))
   :inspector? (parse-uri-bool (.getParameterValue parsed-uri "inspector"))
   :show-landing? (parse-uri-bool (.getParameterValue parsed-uri "show-landing"))
   :x (when-let [x (js/parseFloat (.getParameterValue parsed-uri "x"))]
        (when-not (js/isNaN x) x))
   :y (when-let [y (js/parseFloat (.getParameterValue parsed-uri "y"))]
        (when-not (js/isNaN y) y))
   :z (when-let [z (js/parseFloat (.getParameterValue parsed-uri "z"))]
        (when-not (js/isNaN z) z))
   :utm-campaign (.getParameterValue parsed-uri "utm_campaign")
   :utm-source (.getParameterValue parsed-uri "utm_source")
   :use-talaria? (parse-uri-bool (.getParameterValue parsed-uri "tal"))
   :use-talaria-ajax? (parse-uri-bool (.getParameterValue parsed-uri "ajax"))
   :pessimistic? (parse-uri-bool (.getParameterValue parsed-uri "pessimistic"))})

(defn logging-enabled? []
  (aget js/window "Precursor" "logging-enabled"))

(defn mlog [& messages]
  (when (logging-enabled?)
    (.apply (.-log js/console) js/console (clj->js messages))))

(defn mwarn [& messages]
  (when (logging-enabled?)
    (.apply (.-warn js/console) js/console (clj->js messages))))

(defn merror [& messages]
  (when (logging-enabled?)
    (.apply (.-error js/console) js/console (clj->js messages))))

(defn report-error [& messages]
  (.apply js/Rollbar.error js/Rollbar (clj->js messages))
  (apply merror messages))

(defn log-pr [& args]
  (apply print (map #(if (= (type %) (type ""))
                     %
                     (subs (pr-str %) 0 1000))
                  args)))

(defn md5 [content]
  (let [container (goog.crypt.Md5.)]
    (.update container content)
    (crypt/byteArrayToHex (.digest container))))

(defn notify-error [ch message]
  (put! ch [:error-triggered message]))

(defn trim-middle [s length]
  (let [str-len (count s)]
    (if (<= str-len (+ length 3))
      s
      (let [over (+ (- str-len length) 3)
            slice-pos (.ceil js/Math (/ (- length 3) 3))]
        (str (subs s 0 slice-pos)
             "..."
             (subs s (+ slice-pos over)))))))

(defn third [coll]
  (nth coll 2 nil))

(defn js->clj-kw
  "Same as js->clj, but keywordizes-keys by default"
  [ds]
  (js->clj ds :keywordize-keys true))

(defn cdn-path
  "Returns path of asset in CDN"
  [path]
  (-> js/window
      (aget "Precursor")
      (aget "cdn-base-url")
      (str path)))

(defn edit-input
  "Meant to be used in a react event handler, usually for the :on-change event on input.
  Path is the vector of keys you would pass to assoc-in to change the value in state,
  event is the Synthetic React event. Pulls the value out of the event.
  Optionally takes :value as a keyword arg to override the event's value"
  [controls-ch path event & {:keys [value]
                             :or {value (.. event -target -value)}}]
  (put! controls-ch [:edited-input {:path path :value value}]))

(defn toggle-input
  "Meant to be used in a react event handler, usually for the :on-change event on input.
  Path is the vector of keys you would pass to update-in to toggle the value in state,
  event is the Synthetic React event."
  [controls-ch path event]
  (put! controls-ch [:toggled-input {:path path}]))

(defn rAF
  "Calls passed in function inside a requestAnimationFrame, falls back to timeouts for
   browers without requestAnimationFrame"
  [f]
  (.start (goog.async.AnimationDelay. f)))

(defn strip-html
  "Strips all html characters from the string"
  [str]
  (string/replace str #"[&<>\"']" ""))

(defn set-page-title! [& [title]]
  (set! (.-title js/document) (if (seq title)
                                (strip-html (str title  " | Precursor"))
                                "Precursor—Simple collaborative prototyping")))

(defn valid-email? [str]
  (.isValidAddrSpec EmailAddress str))

(defn deep-merge* [& maps]
  (let [f (fn [old new]
            (if (and (map? old) (map? new))
              (merge-with deep-merge* old new)
              new))]
    (if (every? map? maps)
      (apply merge-with f maps)
      (last maps))))

(defn deep-merge
  "Merge nested maps. At each level maps are merged left to right. When all
  maps have a common key whose value is also a map, those maps are merged
  recursively. If any of the values are not a map then the value from the
  right-most map is chosen.

  E.g.:
  user=> (deep-merge {:a {:b 1}} {:a {:c 3}})
  {:a {:c 3, :b 1}}

  user=> (deep-merge {:a {:b 1}} {:a {:b 2}})
  {:a {:b 2}}

  user=> (deep-merge {:a {:b 1}} {:a {:b {:c 4}}})
  {:a {:b {:c 4}}}

  user=> (deep-merge {:a {:b {:c 1}}} {:a {:b {:e 2 :c 15} :f 3}})
  {:a {:f 3, :b {:e 2, :c 15}}}

  Each of the arguments to this fn must be maps:

  user=> (deep-merge {:a 1} [1 2])
  AssertionError Assert failed: (and (map? m) (every? map? ms))

  Like merge, a key that maps to nil will override the same key in an earlier
  map that maps to a non-nil value:

  user=> (deep-merge {:a {:b {:c 1}, :d {:e 2}}}
                     {:a {:b nil, :d {:f 3}}})
  {:a {:b nil, :d {:f 3, :e 2}}}"
  [& maps]
  (let [maps (filter identity maps)]
    (assert (every? map? maps))
    (apply merge-with deep-merge* maps)))

(defn update-when-in
  "update-in, but only if the nested sequence of keys already exists!"
  [m ks f & args]
  (let [sentinel (js-obj)]
    (if-not (identical? sentinel (get-in m ks sentinel))
      (apply update-in m ks f args)
      m)))

(defn remove-map-nils [unnested-map]
  (into {} (remove (comp nil? last) unnested-map)))

(defn stop-event [e]
  (.stopPropagation e)
  (.preventDefault e))

(defn gravatar-url [email & {:keys [default]
                             :or {default "blank"}}]
  (str "https://www.gravatar.com/avatar/" (md5 (string/lower-case email))
       "?d=" (js/encodeURIComponent default)))

(defn canvas-size []
  (let [node (goog.dom/getElement "canvas-size")]
    (let [size (if node
                 (goog.style/getSize node)
                 (do (mwarn "no #canvas-size element for utils/canvas-size to grab, using viewport")
                     (goog.dom/getViewportSize)))]
      {:width (.-width size)
       :height (.-height size)})))

(defn react-id [x]
  (let [id (aget x "_rootNodeID")]
    (assert id)
    id))

(def select-in seq-util/select-in)

(defn maybe-set-state! [owner korks value]
  (when (not= (om/get-state owner korks) value)
    (om/set-state! owner korks value)))

(defn logged-in? [owner]
  (boolean (seq (om/get-shared owner :cust))))

(defn admin? [owner]
  (boolean (om/get-shared owner :admin?)))

(defn ab-choice [owner test-name]
  (om/get-shared owner [:ab-choices test-name]))

(defn cast-fn [controls-ch]
  (fn [message data & [transient?]]
    (put! controls-ch [message data transient?])))

(defn apply-map [f & args]
  (apply f (apply concat (butlast args) (last args))))

(defn set-canvas-font
  "Takes 2d context, font-size as number (in px), and font-family as string, returns context"
  [context font-size font-family]
  (set! (.-font context) (str font-size "px " font-family))
  context)

(defn measure-text-width
  "Takes text as string, font-size as number (in px), and font-family as string"
  [text font-size font-family]
  (-> (goog.dom/getElement "text-sizer")
    (.getContext "2d")
    (set-canvas-font font-size font-family)
    (.measureText text)
    (.-width)))

(defn to-hex-string [n l]
  (let [s (.toString n 16)
        c (count s)]
    (cond
      (> c l) (subs s 0 l)
      (< c l) (str (apply str (repeat (- l c) "0")) s)
      :else   s)))

(defn squuid []
  (if js/window.crypto
    (let [[b1 b2 b3 b4 b5 b6] (array-seq (js/window.crypto.getRandomValues (js/Uint16Array. 6)) 0)]
      (uuid
       (str
        (-> (js/Date.) (.getTime) (/ 1000) (Math/round) (to-hex-string 8))
        "-" (-> b1 (to-hex-string 4))
        "-" (-> b2 (bit-and 0x0FFF) (bit-or 0x4000) (to-hex-string 4))
        "-" (-> b3 (bit-and 0x3FFF) (bit-or 0x8000) (to-hex-string 4))
        "-" (-> b4 (to-hex-string 4))
        (-> b5 (to-hex-string 4))
        (-> b6 (to-hex-string 4)))))
    ;; Generates a squuid with Math.random
    (d/squuid)))

(defn absolute-css-hash
  "Uses an absolute url to workaround an annoying bug in Firefox where
  the url(#hash) will break if the base url changes."
  [hash]
  (if (ua-browser/isFirefox)
    (str "url(" js/document.location.pathname "#" hash ")")
    (str "url(#" hash ")")))
