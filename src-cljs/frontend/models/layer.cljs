(ns frontend.models.layer
  (:require [datascript :as d]))

(defn selected-eids
  "If the layer is a group, returns the children, else the passed in eid"
  [db selected-eid]
  (let [layer (d/entity db selected-eid)]
    (if (= :layer.type/group (:layer/type layer))
      (conj (:layer/child layer) selected-eid)
      #{selected-eid})))
