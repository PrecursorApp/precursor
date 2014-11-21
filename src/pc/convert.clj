(ns pc.convert
  (:import [javax.imageio ImageIO]
           [org.apache.commons.io FileUtils]
           [java.io ByteArrayOutputStream ByteArrayInputStream]
           [java.awt Color]
           [java.awt.image BufferedImage]
           [org.apache.batik.transcoder.image PNGTranscoder]
           [org.apache.batik.transcoder TranscoderInput TranscoderOutput]))

(defn svg->png [content]
  (let [out (ByteArrayOutputStream.)]
    (with-in-str content
      (.transcode (PNGTranscoder.)
                  (TranscoderInput. *in*)
                  (TranscoderOutput. out)))
    (clojure.java.io/input-stream (.toByteArray out))))
