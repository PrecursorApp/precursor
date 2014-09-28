(ns pc.util)

(defmacro connect-browser-weasel []
  `(do
     (require 'weasel.repl.websocket)
     (cemerick.piggieback/cljs-repl :repl-env (weasel.repl.websocket/repl-env :ip "0.0.0.0" :port 9001))))
