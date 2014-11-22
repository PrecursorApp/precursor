(ns frontend.models.layer
  (:require [clojure.string :as str]
            [datascript :as d]))

(defn selected-eids
  "If the layer is a group, returns the children, else the passed in eid"
  [db selected-eid]
  (let [layer (d/entity db selected-eid)]
    (if (= :layer.type/group (:layer/type layer))
      (conj (:layer/child layer) selected-eid)
      #{selected-eid})))

(defn find-by-ui-id [db ui-id]
  (some->> (d/q '{:find [?t]
                  :in [$ ?id]
                  :where [[?t :layer/ui-id ?id]]}
                db ui-id)
           ffirst
           (d/entity db)))

;; Checks if the layer will be noticed if we put it on the canvas
(defmulti detectable? (fn [layer] (:layer/type layer)))

(defmethod detectable? :default
  [layer]
  (or (not= (:layer/start-x layer)
            (:layer/end-x layer))
      (not= (:layer/start-y layer)
            (:layer/end-y layer))))

;; TODO: serialize points for path
(defmethod detectable? :layer.type/path
  [layer]
  (let [path (:layer/path layer)]
    (and (seq path)
         (not= "M" path)
         (< 1 (count (str/split path #" "))))))

(defmethod detectable? :layer.type/text
  [layer]
  (seq (:layer/text layer)))
