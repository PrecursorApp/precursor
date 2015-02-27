(ns frontend.localstorage
  (:require [cljs.reader :as reader]
            [frontend.utils :as utils :include-macros true]
            [goog.storage.mechanism.HTML5LocalStorage]
            [goog.storage.mechanism.HTML5SessionStorage]))

(defn is-available? [storage-imp]
  (.isAvailable storage-imp))

(defn save!
  "Serializes and saves data to localstorage under key.
   Noops if localstorage isn't available."
  [storage-imp key data]
  (when (is-available? storage-imp)
    (.set storage-imp key (pr-str data))))

(defn read
  "Fetches and deserializes key from localstorage
   Noops if localstorage isn't available."
  [storage-imp key]
  (when (is-available? storage-imp)
    (some-> (.get storage-imp key)
            reader/read-string)))

(defn new-localstorage-imp []
  (goog.storage.mechanism.HTML5LocalStorage.))

(defn new-sessionstorage-imp []
  (goog.storage.mechanism.HTML5SessionStorage.))
