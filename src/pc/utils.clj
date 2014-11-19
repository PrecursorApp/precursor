(ns pc.utils
  (:require clojure.pprint
            clojure.tools.logging))

(defmacro inspect
    "prints the expression '<name> is <value>', and returns the value"
    [value]
    `(do
       (let [value# (quote ~value)
             result# ~value]
         (println value# "is" (with-out-str (clojure.pprint/pprint result#)))
         (clojure.tools.logging/infof "%s is %s" value# result#)
         result#)))

(defmacro connect-browser-weasel []
  `(do
     (require 'weasel.repl.websocket)
     (cemerick.piggieback/cljs-repl :repl-env (weasel.repl.websocket/repl-env :ip "0.0.0.0" :port 9001))))
