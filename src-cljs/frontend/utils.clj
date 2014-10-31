(ns frontend.utils
  (:require [sablono.core :as html]))

(defmacro inspect
  "prints the expression '<name> is <value>', and returns the value"
  [value]
  `(do
     (let [name# (quote ~value)
           result# ~value]
       (print (pr-str name#) "is" (pr-str result#))
       result#)))

(defmacro timing
  "Evaluates expr and prints the label and the time it took.
  Returns the value of expr."
  {:added "1.0"}
  [label expr]
  `(let [global-start# (or (aget js/window "__global_time")
                          (aset js/window "__global_time" (.getTime (js/Date.))))
         start# (.getTime (js/Date.))
         ret# ~expr
         global-time# (- (.getTime (js/Date.)) global-start#)]
     (aset js/window "__global_time" (.getTime (js/Date.)))
     (prn (str ~label " elapsed time: " (- (.getTime (js/Date.)) start#) " ms, " global-time# " ms since last"))
     ret#))

(defmacro swallow-errors
  "wraps errors in a try/catch statement, logging issues to the console
   and optionally rethrowing them if configured to do so."
  [& action]
  `(try ~@action
        (catch :default e#
          (merror e#)
          (when (:rethrow-errors? initial-query-map)
            (throw e#)))))

(defmacro defrender
  "Reifies an IRender component that only has a render function and
   splices the body into the render function"
  [name args & body]
  `(defn ~name ~args 
     (reify
       om.core/IDisplayName
       (~'display-name [~'_] ~(str name))
       om.core/IRender
       (~'render [~'_] ~@body))))

(defmacro html [body]
  `(if-not (:render-colors? initial-query-map)
     (html/html ~body)
     (let [body# ~body]
       (try
         (let [[tag# & rest#] body#
               attrs# (if (map? (first rest#))
                        (first rest#)
                        {})
               rest# (if (map? (first rest#))
                       (rest rest#)
                       rest#)]
           (html/html (vec (concat [tag# (assoc-in attrs# [:style :border] (str "5px solid rgb("
                                                                                (rand-int 255)
                                                                                ","
                                                                                (rand-int 255)
                                                                                ","
                                                                                (rand-int 255)
                                                                                ")"))]
                                   rest#))))
         (catch :default e#
           (html/html body#))))))
