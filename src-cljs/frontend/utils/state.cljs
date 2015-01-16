(ns frontend.utils.state
  (:require [frontend.state :as state]
            [frontend.utils.vcs-url :as vcs-url]
            [frontend.utils.seq :refer [find-index]]))

(defn session-string [state]
  (str (:sente-id state) "-" (:tab-id state)))
