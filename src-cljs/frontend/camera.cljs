(ns frontend.camera)

(defn camera [state]
  (:camera state))

(defn grid-width [state]
  10)

(defn grid-height [state]
  10)

(defn show-bboxes? [state]
  false)

(defn guidelines-enabled? [state]
  false)

(defn show-grid? [state]
  true)
