(ns pc.convert
  (:require [clj-pdf.core :as pdf])
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

;; http://git.io/vflOh
(def a4-size [595 842])

(defn svg->pdf
  "Takes a svg string as content and image width and height
   Will generate a pdf large enough to fit the image, but no smaller than an A4 page"
  [content {:keys [width height]}]
  (let [size [(max (first a4-size) width)
              (max (second a4-size) height)]
        out (ByteArrayOutputStream.)]
    (pdf/pdf
     [{:title "Precursor document"
       :size size
       :footer {:page-numbers false}}
      [:svg content]]
     out)
    (clojure.java.io/input-stream (.toByteArray out))))
