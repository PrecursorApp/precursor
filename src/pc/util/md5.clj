(ns pc.util.md5
  (:require [clojure.java.io :as io])
  (:import java.security.MessageDigest
           org.apache.commons.codec.binary.Hex
           org.apache.commons.io.IOUtils))

(defn byte-array->md5 [ba]
  {:post [(not= "d41d8cd98f00b204e9800998ecf8427e" %)]}
  (Hex/encodeHexString (.digest (MessageDigest/getInstance "MD5") ba)))

(defn md5
  "Takes a file-name and returns the MD5 encoded as a hex string
   Will throw an exception if asked to encode an empty file."
  [file-name]
  (->> file-name
    io/input-stream
    IOUtils/toByteArray
    byte-array->md5))
