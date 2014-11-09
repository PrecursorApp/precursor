(ns pc.util.base64
  (:import org.apache.commons.codec.binary.Base64))

(defprotocol Encodable
  (encode [this]))

(extend-protocol Encodable
  (class (byte-array 0))
  (encode [bytes] (Base64/encodeBase64String bytes))

  String
  (encode [str] (Base64/encodeBase64String (.getBytes str "UTF-8"))))


(defn decode [^String str]
  (String. (Base64/decodeBase64 str) "UTF-8"))
