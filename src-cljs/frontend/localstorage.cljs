(ns frontend.localstorage
  (:require [cljs.reader :as reader]
            [frontend.utils :as utils :include-macros true]
            [goog.storage.mechanism.HTML5LocalStorage]))

(defn is-available? [localstorage-imp]
  (.isAvailable localstorage-imp))

(defn save!
  "Serializes and saves data to localstorage under key.
   Noops if localstorage isn't available."
  [localstorage-imp key data]
  (when (is-available? localstorage-imp)
    (.set localstorage-imp key (pr-str data))))

(defn read
  "Fetches and deserializes key from localstorage
   Noops if localstorage isn't available."
  [localstorage-imp key]
  (when (is-available? localstorage-imp)
    (some-> (.get localstorage-imp key)
            reader/read-string)))

(defn new-localstorage-imp []
  (goog.storage.mechanism.HTML5LocalStorage.))
