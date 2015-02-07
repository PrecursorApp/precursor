(ns ^:figwheel-no-load frontend.dev
  (:require [figwheel.client :as figwheel :include-macros true]
            [frontend.core :as core]
            [frontend.utils :as utils :include-macros true]
            [weasel.repl :as ws-repl]))

(defn setup-browser-repl []
  ;; this is harmless if it fails
  (ws-repl/connect "ws://localhost:9001" :verbose true)
  ;; the repl tries to take over *out*, workaround for
  ;; https://github.com/cemerick/austin/issues/49
  (js/setInterval #(cljs.core/enable-console-print!) 1000))

(defn setup-figwheel [{:keys [js-callback]}]
  (figwheel/start {:on-jsload js-callback
                   :websocket-url "ws://localhost:3448/figwheel-ws"}))

(utils/swallow-errors (setup-browser-repl))
(utils/swallow-errors (frontend.dev/setup-figwheel {:js-callback core/om-setup-debug}))
