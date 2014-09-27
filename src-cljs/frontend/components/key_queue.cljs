(ns frontend.components.key-queue
  "The key function is KeyboardHandler, which takes a KEYMAP, a map
  from key combinations as descibed in event->key and match-keys to
  functions of no arguments.  This component sets up a loop which
  checks for key combinations in KEYMAP, calling the associated
  function when they happen.  Key sequences can be represented as
  strings, with consecutive key events separated by a space.  Keys in
  key combinations need to be pressed within one second, or the loop
  forgets about them."
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [frontend.async :refer [put!]]
            [om.core :as om :include-macros true]
            [clojure.string :refer [join split]]
            [dommy.core :as dommy]
            [sablono.core :as html :include-macros true]
            [frontend.utils :as utils :include-macros true])
  (:require-macros [cljs.core.async.macros :as async]))

(def code->key
  "map from a character code (read from events with event.which)
  to a string representation of it.
  Only need to add 'special' things here."
  {13 "enter"
   27 "esc"
   37 "left"
   38 "up"
   39 "right"
   40 "down"
   46 "del"
   186 ";"
   191 "slash"})
 
(defn event-modifiers
  "Given a keydown event, return the modifier keys that were being held."
  [e]
  (into [] (filter identity [(if (.-shiftKey e) "shift")
                             (if (.-altKey e)   "alt")
                             (if (.-ctrlKey e)  "ctrl")
                             (if (.-metaKey e)  "meta")])))
 
(def mod-keys
  "A vector of the modifier keys that we use to compare against to make
  sure that we don't report things like pressing the shift key as independent events.
  This may not be desirable behavior, depending on the use case, but it works for
  what I need."
  [;; shift
   (js/String.fromCharCode 16)
   ;; ctrl
   (js/String.fromCharCode 17)
   ;; alt
   (js/String.fromCharCode 18)])
 
(defn event->key
  "Given an event, return a string like 'up' or 'shift+l' or 'ctrl+;'
  describing the key that was pressed.
  This fn will never return just 'shift' or any other lone modifier key."
  [event]
  (let [mods (event-modifiers event)
        which (.-which event)        
        key (or (code->key which) (.toLowerCase (js/String.fromCharCode which)))]  
    (if (and key (not (empty? key)) (not (some #{key} mod-keys)))
      (join "+" (conj mods key)))))

(defn log-keystroke [e]
  (utils/mlog "key event" e) e)

(defn start-key-queue [key-ch]
  (dommy/listen! js/document :keydown
                 #(when-let [k (event->key %)]
                    (log-keystroke k)
                    (async/put! key-ch k))))

(def global-key-ch
  (->> 1000 async/sliding-buffer async/chan))

(start-key-queue global-key-ch)

(def key-mult
  (async/mult global-key-ch))

(defn combo-match? [keys combo]
  (let [tail-keys  (->> keys (iterate rest) (take-while seq))]
    (some (partial = combo) tail-keys)))

(defn combos-match? [combo-or-combos keys]
  (let [combos (if (coll? combo-or-combos)
                 combo-or-combos [combo-or-combos])
        combos (map #(split % #" ") combos)]
    (some (partial combo-match? keys) combos)))

(defn match-keys
  "Given a keymap for the component and the most recent series of keys
  that were pressed (not the codes, but strings like 'shift+r' and
  stuff) return a handler fn associated with a key combo in the keys
  list or nil."
  [keymap keys]
  (->> keymap (keep (fn [[c f]] (if (combos-match? c keys) f))) first))

(defn KeyboardHandler [app owner {:keys [keymap error-ch]}]
  (reify
    om/IDisplayName (display-name [_] "Keyboard Handler")
    om/IDidMount
    (did-mount [_]
      (let [ch (chan)]
        (om/set-state! owner :ch ch)
        (async/tap key-mult ch)
        (async/go-loop [waiting-keys []
                        t-chan nil]
                       (let [t-chan        (or t-chan (async/chan))
                             [e read-chan] (async/alts! [ch t-chan])]
                         (if (= read-chan ch)
                           (let [all-keys (conj waiting-keys e)]
                             (if-let [key-fn (match-keys @keymap all-keys)]
                               (do (try (key-fn e)
                                        ;; Catch any errors to avoid breaking key loop
                                        (catch js/Object error
                                          (utils/merror error "Error calling" key-fn "with key event" e)
                                          (put! error-ch [:keyboard-handler-error error])))
                                   (recur [] nil))
                               ;; No match yet, but remember in case user is entering
                               ;; a multi-key combination.
                               (recur all-keys (async/timeout 1000))))
                           ;; Read channel was timeout.  Forget stored keys
                           (recur [] nil))))))
    om/IWillUnmount
    (will-unmount [_]
      (let [ch (om/get-state owner :ch)]
        (async/untap key-mult ch)))
    om/IRender
    (render [_]
      (html/html [:span.hidden]))))
