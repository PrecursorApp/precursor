(ns pc.version
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn version []
  (str/trim (slurp (io/resource "version"))))

(defn -main
  "The version file is created by the build process, just before it creates an uberjar"
  []
  (println (version)))
