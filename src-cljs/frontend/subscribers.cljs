(ns frontend.subscribers
  (:require [frontend.utils :as utils]))

(defn subscriber-entity-ids [app-state]
  (reduce (fn [acc [id data]]
            (apply conj acc (map :db/id (:layers data))))
          #{} (get-in app-state [:subscribers :layers])))

(defn update-subscriber-entity-ids [app-state]
  (assoc-in app-state [:subscribers :entity-ids :entity-ids] (subscriber-entity-ids app-state)))

(defn add-subscriber-data [app-state client-id subscriber-data]
  (let [mouse-data (select-keys subscriber-data [:mouse-position :show-mouse? :tool :color])
        layer-data (select-keys subscriber-data [:layers :color])
        info-data (select-keys subscriber-data [:color :cust-name :show-mouse? :hide-in-list? :frontend-id-seed])]
    (cond-> app-state
      (seq mouse-data) (update-in [:subscribers :mice client-id] merge mouse-data)
      (seq layer-data) (update-in [:subscribers :layers client-id] merge layer-data)
      (seq info-data) (update-in [:subscribers :info client-id] merge info-data)
      true update-subscriber-entity-ids)))


(defn maybe-add-subscriber-data [app-state client-id subscriber-data]
  (if (get-in app-state [:subscribers :info client-id])
    (add-subscriber-data app-state client-id subscriber-data)
    app-state))



(defn remove-subscriber [app-state client-id]
  (-> app-state
    (update-in [:subscribers :mice] dissoc client-id)
    (update-in [:subscribers :layers] dissoc client-id)
    (update-in [:subscribers :info] dissoc client-id)
    (update-subscriber-entity-ids)))
