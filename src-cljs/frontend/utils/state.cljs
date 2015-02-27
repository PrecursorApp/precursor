(ns frontend.utils.state
  (:require [frontend.state :as state]
            [frontend.utils.seq :refer [find-index]]))

(defn session-string [state]
  (str (:sente-id state) "-" (:tab-id state)))
