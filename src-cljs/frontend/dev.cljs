(ns frontend.dev
  (:require [figwheel.client :as figwheel :include-macros true]
            [weasel.repl :as ws-repl]))

(defn setup-browser-repl []
  ;; this is harmless if it fails
  (ws-repl/connect "ws://localhost:9001" :verbose true)
  ;; the repl tries to take over *out*, workaround for
  ;; https://github.com/cemerick/austin/issues/49
  (js/setInterval #(enable-console-print!) 1000))

(defn setup-figwheel [{:keys [js-callback]}]
  (figwheel/start {:on-jsload js-callback
                   :websocket-url "ws://localhost:3448/figwheel-ws"}))
