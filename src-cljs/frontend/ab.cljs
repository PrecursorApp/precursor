(ns frontend.ab
  (:require [goog.net.Cookies]
            [goog.crypt.Md5]
            [frontend.analytics.mixpanel :as mixpanel]
            [frontend.utils :as utils :include-macros true]))

(def cookie-name "ab_test_user_seed")

(defn ^:export get-user-seed []
  (.get (goog.net.Cookies. js/document) cookie-name))

(defn set-user-seed! []
  (let [random-seed (str (Math/random))]
    (utils/mlog "setting ab-test seed to" random-seed)
    (.set (goog.net.Cookies. js/document) cookie-name random-seed 365 "/" false)))

(defn get-int [bytes]
  (assert (= 4 (count bytes)))
  (let [[a b c d] bytes]
    (bit-xor (bit-shift-left (bit-and a 255) 24)
             (bit-shift-left (bit-and b 255) 16)
             (bit-shift-left (bit-and c 255) 8)
             (bit-shift-left (bit-and d 255) 0))))

(defn first-md5-word
  "Using the same terminology as CrytoJS here. Returns the first 32-bit int from the first 4 md5 bytes."
  [string]
  (let [container (doto (goog.crypt.Md5.) (.update string))
        md5-bytes (.digest container)]
    (get-int (take 4 md5-bytes))))

(defn ^:export choose-option-index [seed test-name options]
  (let [word (first-md5-word (str seed (name test-name)))]
    (Math/abs (mod word (if (neg? word)
                          (- (count options))
                          (count options))))))

(defn choose [seed test-definitions & {:keys [overrides] :or {overrides {}}}]
  (merge (reduce (fn [choices [test-name options]]
                   (assoc choices test-name (nth options (choose-option-index seed test-name options))))
                 {} test-definitions)
          overrides))

(defn notify-mixpanel [choices]
  (let [mixpanel-choices (reduce (fn [m-choices [key value]]
                                   ;; rename keys from :test-name to "ab_test-name"
                                   (assoc m-choices (str "ab_" (name key)) value))
                                 {} choices)]
    (mixpanel/register-once mixpanel-choices)))

(defn setup! [test-definitions & {:keys [overrides] :or {overrides {}}}]
  (let [seed (or (get-user-seed)
                 (do
                   (set-user-seed!)
                   (get-user-seed)))
        choices (choose seed test-definitions :overrides overrides)]
    (notify-mixpanel choices)
    choices))
