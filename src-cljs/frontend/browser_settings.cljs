(ns frontend.browser-settings
  (:require [frontend.localstorage :as localstorage]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]))

(def browser-settings-key "circle-browser-settings")

(defn restore-browser-settings! [localstorage-imp state-atom]
  (swap! state-atom update-in state/browser-settings-path merge (localstorage/read localstorage-imp browser-settings-key)))

(defn browser-settings-watcher [localstorage-imp key ref old-data new-data]
  (when (not (identical? (get-in old-data state/browser-settings-path)
                         (get-in new-data state/browser-settings-path)))
    (localstorage/save! localstorage-imp browser-settings-key (get-in new-data state/browser-settings-path))))

(defn add-browser-settings-watcher [localstorage-imp state-atom]
  (add-watch state-atom browser-settings-key (partial browser-settings-watcher localstorage-imp)))

(defn setup! [state-atom]
  (let [localstorage-imp (localstorage/new-localstorage-imp)]
    (restore-browser-settings! localstorage-imp state-atom)
    (add-browser-settings-watcher localstorage-imp state-atom)))
