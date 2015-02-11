(ns frontend.disposable)

;;TODO Automatic-disposal on Om/React unmount.

(def counter (atom 0))

(def disposables (atom {}))

(defn register [value finalizer]
  (let [id (swap! counter inc)]
    (swap! disposables assoc id {:value value :finalizer finalizer})
    id))

(defn from-id [id]
  (get-in @disposables [id :value]))

(defn dispose [id]
  (when-let [{:keys [value finalizer]} (@disposables id)]
    (finalizer value)
    nil))
