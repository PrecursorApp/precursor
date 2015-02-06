(ns pc.version
  (:require [clojure.java.io :as io]))

(defn -main
  "The version file is created by the build process, just before it creates an uberjar"
  []
  (println (slurp (io/resource "version"))))
