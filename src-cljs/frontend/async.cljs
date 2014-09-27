(ns frontend.async
  (:require [cljs.core.async :as async]))

(def ^:dynamic *uuid* nil)

(defn put! [port val & args]
  (if (and (satisfies? IMeta val) *uuid*)
    (apply async/put! port (vary-meta val assoc :uuid *uuid*) args)
    (apply async/put! port val args)))
