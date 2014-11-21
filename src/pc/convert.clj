(ns pc.convert
  (:import [java.io ByteArrayOutputStream ByteArrayInputStream]
           [org.apache.batik.transcoder.image PNGTranscoder]
           [org.apache.batik.transcoder TranscoderInput TranscoderOutput]))

(defn svg->png [content]
  (let [out (ByteArrayOutputStream.)]
    (with-in-str content
      (.transcode (PNGTranscoder.)
                  (TranscoderInput. *in*)
                  (TranscoderOutput. out)))
    (clojure.java.io/input-stream (.toByteArray out))))
