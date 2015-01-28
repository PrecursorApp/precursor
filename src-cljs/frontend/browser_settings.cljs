(ns frontend.browser-settings
  (:require [clojure.set :as set]
            [frontend.localstorage :as localstorage]
            [frontend.state :as state]
            [frontend.sente :as sente]
            [frontend.utils :as utils :include-macros true]))

(def browser-settings-key "circle-browser-settings")

(def db-setting->app-state-setting
  {:browser-setting/tool :current-tool
   :browser-setting/chat-opened :chat-opened
   :browser-setting/chat-mobile-toggled :chat-mobile-toggled
   :browser-setting/right-click-learned :right-click-learned
   :browser-setting/menu-button-learned :menu-button-learned
   :browser-setting/info-button-learned :info-button-learned
   :browser-setting/newdoc-button-learned :newdoc-button-learned
   :browser-setting/login-button-learned :login-button-learned
   :browser-setting/your-docs-learned :your-docs-learned
   :browser-setting/main-menu-learned :main-menu-learned
   :browser-setting/invite-menu-learned :invite-menu-learned
   :browser-setting/sharing-menu-learned :sharing-menu-learned
   :browser-setting/shortcuts-menu-learned :shortcuts-menu-learned
   :browser-setting/chat-menu-learned :chat-menu-learned})

(def app-state-setting->db-setting
  (set/map-invert db-setting->app-state-setting))

(defn merged-settings [app-state-settings db-settings local-settings]
  (utils/deep-merge app-state-settings
                    local-settings
                    db-settings))

(defn restore-browser-settings [state cust]
  (let [localstorage-imp (localstorage/new-localstorage-imp)
        local-settings (localstorage/read localstorage-imp browser-settings-key)
        db-settings (-> cust
                      (select-keys (keys db-setting->app-state-setting))
                      (set/rename-keys db-setting->app-state-setting))]
    (update-in state state/browser-settings-path merged-settings db-settings local-settings)))

(defn diff-from-db-settings [cust settings]
  (reduce (fn [acc [k v]]
            (let [db-key (get app-state-setting->db-setting k)]
              (if (and (not= v (get state/initial-browser-settings k))
                       (not= v (get cust db-key)))
                (assoc acc db-key v)
                acc)))
          {} settings))

(defn diff [before after]
  (reduce (fn [acc [k v]]
            (if (not= v (get before k))
              (assoc acc k v)
              acc))
          {} after))

(defn browser-settings-watcher [localstorage-imp key ref old-data new-data]
  (when (not (identical? (get-in old-data state/browser-settings-path)
                         (get-in new-data state/browser-settings-path)))
    (localstorage/save! localstorage-imp browser-settings-key (get-in new-data state/browser-settings-path))
    (let [changes (utils/inspect (diff (get-in old-data state/browser-settings-path)
                                       (get-in new-data state/browser-settings-path)))]
      (let [db-changes (select-keys changes (keys app-state-setting->db-setting))]
        (when (seq db-changes)
          (sente/send-msg (:sente @ref)
                          [:frontend/save-browser-settings
                           {:settings (set/rename-keys db-changes app-state-setting->db-setting)}]))))))

(defn add-browser-settings-watcher [localstorage-imp state-atom]
  (add-watch state-atom browser-settings-key (partial browser-settings-watcher localstorage-imp)))

(defn setup! [state-atom]
  (let [localstorage-imp (localstorage/new-localstorage-imp)]
    (localstorage/save! localstorage-imp browser-settings-key
                        (get-in @state-atom state/browser-settings-path))
    (add-browser-settings-watcher localstorage-imp state-atom)
    (when (:cust @state-atom)
      (let [db-changes (diff-from-db-settings (:cust @state-atom)
                                              (get-in @state-atom state/browser-settings-path))]
        (when (seq db-changes)
          (sente/send-msg (:sente @state-atom) [:frontend/save-browser-settings {:settings db-changes}]))))))
