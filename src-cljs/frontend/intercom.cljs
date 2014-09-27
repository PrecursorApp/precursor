(ns frontend.intercom
  (:require [frontend.utils :as utils :include-macros true]))

(defn intercom-jquery []
  (aget js/window "intercomJQuery"))

(defn raise-dialog [ch & [message]]
  (if-let [jq (intercom-jquery)]
    (try
      (.click (jq "#IntercomTab"))
      (when-not (.is (jq "#IntercomNewMessageContainer") ":visible")
        (.click (jq "[href=IntercomNewMessageContainer]")))
      (.focus (jq "#newMessageBody"))
      (when message
        (.text (jq "#newMessageBody") (str message "\n\n")))
      (catch :default e
        (utils/notify-error ch "Uh-oh, our Help system isn't available. Please email us instead, at sayhi@circleci.com")
        (utils/merror e)))
    (utils/notify-error ch "Uh-oh, our Help system isn't available. Please email us instead, at sayhi@circleci.com")))

(defn user-link []
  (let [path (.. js/window -location -pathname)
        re (js/RegExp. "/gh/([^/]+/[^/]+)")]
    (when-let [match (.match path re)]
      (str "https://www.intercom.io/apps/vnk4oztr/users"
           "?utf8=%E2%9C%93"
           "&filters%5B0%5D%5Battr%5D=custom_data.pr-followed"
           "&filters%5B0%5D%5Bcomparison%5D=contains&filters%5B0%5D%5Bvalue%5D="
           (second path)))))
