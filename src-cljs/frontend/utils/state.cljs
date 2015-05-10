(ns frontend.utils.state
  (:require [frontend.state :as state]
            [frontend.utils.seq :refer [find-index]]))

(defn session-string [state]
  (str (:sente-id state) "-" (:tab-id state)))

(defn client-id->user [app client-id]
  (if (= client-id (:client-id app))
    "you"
    (let [cust-uuid (get-in app [:subscribers :info client-id :cust/uuid])]
      (get-in app [:cust-data :uuid->cust cust-uuid :cust/name] (apply str (take 6 client-id))))))

(defn chat-width [viewport-size]
  25)

(defn canvas-width [viewport-size]
  (- 100 (chat-width viewport-size)))
