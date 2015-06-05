(ns frontend.ab
  (:require [goog.net.Cookies]
            [goog.testing.PseudoRandom]
            [goog.crypt.Md5]))

(def cookie-name "ab_test_user_seed")

(defn ^:export get-user-seed []
  (.get (goog.net.Cookies. js/document) cookie-name))

(defn set-user-seed! []
  (let [random-seed (str (Math/random))]
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

(defn setup! [test-definitions & {:keys [overrides] :or {overrides {}}}]
  (let [seed (or (get-user-seed)
                 (do
                   (set-user-seed!)
                   (get-user-seed)))
        choices (choose seed test-definitions :overrides overrides)]
    choices))

(defn avg [& coll]
  (/ (reduce + coll) (count coll)))

(defn choose-is-fairish? []
  ;; https://en.wikipedia.org/wiki/Checking_whether_a_coin_is_fair
  (let [n 1000
        Z 4.4172                        ; 99.99% confidence
        E (/ Z (* 2 (Math/sqrt n)))
        seed (js/Math.random)]
    (> E (Math/abs (- 1
                      (apply avg (map #(choose-option-index seed (str %) [0 1 2])
                                      (range n))))))))
