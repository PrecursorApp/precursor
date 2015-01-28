(ns pc.gzip
  (:require [clojure.java.io :as io])
  (:import [java.util.zip GZIPOutputStream]
           [org.apache.commons.io IOUtils]))

(defn gzip [file]
  (with-open [is (io/input-stream file)]
    (let [data-to-compress (IOUtils/toByteArray is)]
      (with-open [os (java.io.ByteArrayOutputStream.)]
        (with-open [zipped-os (GZIPOutputStream. os)]
          (IOUtils/copy (java.io.ByteArrayInputStream. data-to-compress) zipped-os))
        (java.io.ByteArrayInputStream. (.toByteArray os))))))
