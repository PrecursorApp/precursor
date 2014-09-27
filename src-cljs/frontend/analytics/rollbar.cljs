(ns frontend.analytics.rollbar)

(defn init-user [login]
  (try
   (aset (aget js/window "_rollbarParams") "person" #js {:id login})
   (catch :default e
     (js/console.log e))))
