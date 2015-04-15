(ns frontend.db.trans
  (:require [datascript :as d]))

;; Need a better way to accomplish this--perhaps a separate transient db?
;; lets us use the first 5000 ids for things like bot chats and bot drawings
(defonce transient-ids (atom {}))

(defn get-next-transient-id [conn]
  (loop [id (get @transient-ids conn 1)]
    (let [ids @transient-ids]
      (if (or (first (d/datoms @conn :eavt id))
              (not (compare-and-set! transient-ids ids (assoc-in ids [conn] (inc id)))))
        (recur (inc id))
        id))))

(defn reset-id [conn]
  (swap! transient-ids assoc conn 1))
