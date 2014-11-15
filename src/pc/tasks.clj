(ns pc.tasks
  (:require [pc.less :as less]))

(defn compile-less []
  (less/compile!)
  (System/exit 0))
