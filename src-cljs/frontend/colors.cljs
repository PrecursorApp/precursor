(ns frontend.colors
  (:require [clojure.string :as str]))

(def color-idents
  [:color.name/red
   :color.name/orange
   :color.name/yellow
   :color.name/green
   :color.name/cyan
   :color.name/blue
   :color.name/purple
   :color.name/pink])

(defn uuid-part->long [uuid-part]
  (js/parseInt (str "0x" uuid-part) 16))

(defn choose-from-uuid [choices uuid]
  (let [parts (str/split (str uuid) #"-")
        num (-> (uuid-part->long (first parts))
              (bit-shift-left 16)
              (bit-or (uuid-part->long (second parts)))
              (bit-shift-left 16)
              (bit-or (uuid-part->long (nth parts 2))))]
    (nth choices (mod (js/Math.abs num) (count choices)))))

(defn color-class [uuid->cust cust-uuid alt-uuid]
  (name (or (get-in uuid->cust [cust-uuid :cust/color-name])
            (choose-from-uuid color-idents alt-uuid))))
