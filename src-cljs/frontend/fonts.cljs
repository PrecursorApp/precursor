(ns frontend.fonts
  (:require [cljs-http.client :as http]
            [cljs.core.async :as async]
            [cljs.core.async.impl.buffers :as buffers]
            [goog.crypt]
            [goog.crypt.base64 :as base64]
            [frontend.utils :as utils])
  (:require-macros [cljs.core.async.macros :refer (go)]))

(defonce font-promise-chs (let [roboto-buffer (buffers/promise-buffer)
                                fa-buffer (buffers/promise-buffer)]
                            ;; need the buffer so that we can count
                            {"Roboto" {:buffer roboto-buffer
                                      :ch (async/chan roboto-buffer nil nil)}
                             "FontAwesome" {:buffer fa-buffer
                                           :ch (async/chan fa-buffer nil nil)}}))

(defn font-ch [font-name]
  (get-in font-promise-chs [font-name :ch]))

(def font-paths {"Roboto" (utils/cdn-path "/webfonts/roboto-v15-latin-regular.ttf")
                 "FontAwesome" (utils/cdn-path "/webfonts/fontawesome-webfont.ttf")})

;; Note that this could try to fetch the same font multiple times, but
;; will always return as soon as the first fetch finishes.
(defn fetch-font
  "Fetches the font and puts it in the font-promise-chan base64-encoded."
  [font-name]
  (let [{:keys [buffer ch]} (get font-promise-chs font-name)
        font-path (get font-paths font-name)]
    (when (zero? (count buffer))
      (go
        (let [font-str (-> (http/get (str font-path "?xhr=true") ;; cache buster for cors
                                     {:response-type :array-buffer})
                         (async/<!)
                         (:body)
                         (js/Uint8Array.)
                         (base64/encodeByteArray))]
          (async/>! ch {:font-name font-name :font-str font-str}))))))
