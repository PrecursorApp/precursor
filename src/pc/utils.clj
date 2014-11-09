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
