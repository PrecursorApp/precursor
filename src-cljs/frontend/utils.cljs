(ns frontend.utils
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.string :as string]
            [frontend.async :refer [put!]]
            [om.core :as om :include-macros true]
            [ajax.core :as ajax]
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
            [frontend.utils.seq :as seq-util])
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
        (when-not (js/isNaN z) z))})

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

(defn log-pr [& args]
  (apply print (map #(if (= (type %) (type ""))
                     %
                     (subs (pr-str %) 0 1000))
                  args)))

(defn uuid
  "returns a type 4 random UUID: xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx"
  []
  (let [r (repeatedly 30 (fn [] (.toString (rand-int 16) 16)))]
    (apply str (concat (take 8 r) ["-"]
                       (take 4 (drop 8 r)) ["-4"]
                       (take 3 (drop 12 r)) ["-"]
                       [(.toString  (bit-or 0x8 (bit-and 0x3 (rand-int 15))) 16)]
                       (take 3 (drop 15 r)) ["-"]
                       (take 12 (drop 18 r))))))

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
  (om/get-shared owner :logged-in?))

(defn cast-fn [controls-ch]
  (fn [message data & [transient?]]
    (put! controls-ch [message data transient?])))

(defn apply-map [f & args]
  (apply f (apply concat (butlast args) (last args))))
