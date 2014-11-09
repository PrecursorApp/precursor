(ns pc.util.jwt
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [pc.util.base64 :as base64]))

(defn decode [jwt-str]
  (let [[header payload signature] (str/split jwt-str #"\.")]
    {:header (-> header base64/decode (json/decode true))
     :payload (-> payload base64/decode (json/decode true))
     :signature signature}))
