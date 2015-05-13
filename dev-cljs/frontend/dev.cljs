(ns ^:figwheel-no-load frontend.dev
    (:require [frontend.careful]
              [frontend.core :as core]
              [frontend.utils :as utils :include-macros true]
              [weasel.repl :as ws-repl]))

(defn setup-browser-repl []
  ;; this is harmless if it fails
  (ws-repl/connect "ws://localhost:9001" :verbose true)
  ;; the repl tries to take over *out*, workaround for
  ;; https://github.com/cemerick/austin/issues/49
  (js/setInterval #(cljs.core/enable-console-print!) 1000))

(utils/swallow-errors (setup-browser-repl))
(defn jsload []
  (@frontend.careful/om-setup-debug))
