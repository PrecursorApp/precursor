(ns frontend.components.key-queue
  "Cribbed from https://gist.github.com/tomconnors/8460406 .  We
  should probably rewrite.

  The key function is keyboard-handler, which takes a KEYMAP, a map
  from key combinations as descibed in event->key and match-keys to
  functions of no arguments.  This component sets up a loop which
  checks for key combinations in KEYMAP, calling the associated
  function when they happen.  Key sequences can be represented as
  strings, with consecutive key events separated by a space.  Keys in
  key combinations need to be pressed within one second, or the loop
  forgets about them."
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer put! close!]]
            [clojure.string :refer [join split]]
            [frontend.utils :as utils :include-macros true]
            [goog.events]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [cljs.core.async.macros :as async]))

(def code->key
  "map from a character code (read from events with event.which)
  to a string representation of it.
  Only need to add 'special' things here."
  {8   "backspace"
   13  "enter"
   16  "shift"
   17  "ctrl"
   18  "alt"
   27  "esc"
   33 "pageup"
   34 "pagedown"
   36  "home"
   37  "left"
   38  "up"
   39  "right"
   40  "down"
   46  "del"
   91  "meta"
   32  "space"
   186 ";"
   191 "/"
   219 "["
   221 "]"
   187 "="
   189 "-"
   190 "."
   220 "\\"})
 
(defn event-modifiers
  "Given a keydown event, return the modifier keys that were being held."
  [e]
  (set (filter identity [(when (.-shiftKey e) "shift")
                         (when (.-altKey e)   "alt")
                         (when (.-ctrlKey e)  "ctrl")
                         (when (.-metaKey e)  "meta")])))
 
(def mod-keys
  "A vector of the modifier keys that we use to compare against to make
  sure that we don't report things like pressing the shift key as independent events.
  This may not be desirable behavior, depending on the use case, but it works for
  what I need."
  #{"alt" "ctrl" "shift"})
 
(defn event->key
  "Given an event, return a string like 'up' or 'shift+l' or 'ctrl+;'
  describing the key that was pressed. Will return lone modifier keys, like shift or ctrl"
  [event]
  (let [mods (event-modifiers event)
        code (.-keyCode event)
        key (or (code->key code) (.toLowerCase (js/String.fromCharCode code)))]
    (if (and key (not (empty? key)))
      (conj (set mods) key))))

(defn log-keystroke [e]
  (utils/mlog "key event" e) e)

(defn start-key-queue [key-ch]
  (goog.events/listen js/document "keydown"
                      #(when-let [k (event->key %)]
                         (async/put! key-ch k))))

(def global-key-ch
  (->> 1000 async/sliding-buffer async/chan))

(start-key-queue global-key-ch)

(def key-mult
  (async/mult global-key-ch))

(defn match-keys
  "Given a keymap for the component and the most recent series of keys
  that were pressed (not the codes, but sets of keys like #{'shift' 'r'})
  return a handler fn associated with a key combo in the keys
  list or nil."
  [keymap keys]
  (->> keymap
       (keep (fn [[m c]]
               (when (contains? keys m) c)))
       first))

(defn keyboard-handler [payload owner opts]
  (reify
    om/IDisplayName
    (display-name [_]
      (or (:react-name opts) "KeyboardHandler"))
    om/IWillMount
    (will-mount [_]
      (let [{:keys [cast!]}   opts
            key-press-ch      (chan)
            key-map           (om/value (get-in payload [:key-map]))
            key-map-update-ch (chan)
            unmount-ch        (chan)
            my-name           (om/value (get-in payload [:name]))]
        (om/set-state! owner :key-map-update-ch key-map-update-ch)
        (om/set-state! owner :key-press-ch key-press-ch)
        (om/set-state! owner :unmount-ch unmount-ch)
        (async/tap key-mult key-press-ch)
        (async/go
          (loop [waiting-keys #{}
                 t-chan       nil
                 key-map      key-map]
            (async/alt!
              key-press-ch ([e]
                            (let [all-keys (conj waiting-keys e)]
                              (if-let [combo-fn (match-keys key-map all-keys)]
                                (do
                                  (try (combo-fn)
                                       ;; Catch any errors to avoid breaking key loop
                                       (catch js/Error error
                                         (utils/merror error "Error handling keyboard")))
                                  (recur #{} nil key-map))
                                ;; No match yet, but remember in case user is entering
                                ;; a multi-key combination.
                                (recur all-keys (async/timeout 1000) key-map))))
              key-map-update-ch ([new-key-map]
                                 (recur waiting-keys t-chan new-key-map))
              ;; Read channel was timeout.  Forget stored keys
              (or t-chan (async/timeout 1000)) (recur #{} nil key-map)
              (async/chan) (recur #{} nil key-map)
              unmount-ch (print "Unmounted; destroying key-queue loop for " my-name))))))
    om/IWillUnmount
    (will-unmount [_]
      (async/untap key-mult (om/get-state owner :key-press-ch))
      (put! (om/get-state owner :unmount-ch) [:stop]))
    om/IRender
    (render [_]
      (let [new-key-map     (om/value (get-in payload [:data :key-map]))
            current-key-map (om/get-state owner :key-map)]
        (when (and new-key-map (not= current-key-map new-key-map))
          (if-let [updated-ch (om/get-state owner :key-map-update-ch)]
            (put! updated-ch new-key-map)
            (print "No update-ch to notify new keybindings for " (get-in payload [:data :name])))))
      (dom/span #js {:className "hidden"}))))
