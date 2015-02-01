(ns frontend.components.sketch
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [frontend.components.canvas :as canvas]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [frontend.db :as ds]
            [datascript :as d]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

(defn initial-state []
  {:states {:a (assoc (state/initial-state) :subscribers {"b" {:show-mouse? true
                                                               :color "red"}})
            :b (assoc (state/initial-state) :subscribers {"a" {:show-mouse? true
                                                               :color "green"}})}
   :last-tick (atom 0)
   :end-ticks 10
   :tick-ms 60})

(defn reset-state! [owner]
  (om/update-state! owner (fn [s]
                            (-> s
                              (merge (initial-state))
                              (update-in [:db] ds/reset-db!)))))

(defn run-animation [owner]
  (when (om/mounted? owner)
    (let [ticks (om/get-state owner :ticks)
          tick (swap! (om/get-state owner :last-tick) inc)
          _ (om/set-state! owner :dummy (rand-int 10000))]
      (if (> tick (+ (:max-ticks ticks) (om/get-state owner :end-ticks)))
        (do
          (reset-state! owner)
          (d/transact! (om/get-state owner :db) [{:db/id -1 :dummy true}]))
        (when-let [tick-fn (get ticks tick)]
          (om/update-state! owner [:states] (fn [s] (tick-fn s)))))
      (js/setTimeout #(run-animation owner) (om/get-state owner :tick-ms)))))

(defn add-shape [tick-state owner {:keys [type start-tick end-tick client start-x end-x start-y end-y rx ry]
                                   :or {rx 0 ry 0}}]
  (let [layer-id (rand-int 5000)
        other-client (if (= :a client) :b :a)]
    (-> (reduce (fn [tick-state relative-tick]
                  (update-in tick-state [(+ start-tick relative-tick)]
                             (fn [f]
                               (fn [states]
                                 (let [ex (+ start-x (* relative-tick (/ (- end-x start-x) (- end-tick start-tick 3))))
                                       ey (+ start-y (* relative-tick (/ (- end-y start-y) (- end-tick start-tick 3))))
                                       layer {:layer/type (keyword "layer.type" (name type))
                                              :layer/start-x start-x
                                              :layer/start-y start-y

                                              :layer/current-x ex
                                              :layer/end-x ex
                                              :layer/current-y ey
                                              :layer/end-y ey
                                              :layer/rx rx
                                              :layer/ry ry
                                              :db/id layer-id}]
                                   (-> (if f
                                         (f states)
                                         states)
                                     (assoc-in [client :drawing]
                                               {:in-progress? true
                                                :layers [layer]})
                                     (update-in [other-client :subscribers (name client)]
                                                (fn [s]
                                                  (-> s
                                                    (assoc :layers [layer]
                                                           :tool :rect
                                                           :mouse-position [ex ey]))))))))))
                tick-state
                (range (- end-tick start-tick 2)))
      (update-in [:max-ticks] (fnil max 0) end-tick)
      (update-in [end-tick] (fn [f]
                              (fn [states]
                                (d/transact! (om/get-state owner :db) [{:layer/type (keyword "layer.type" (name type))
                                                                        :layer/start-x start-x
                                                                        :layer/end-x end-x
                                                                        :layer/start-y start-y
                                                                        :layer/end-y end-y
                                                                        :layer/rx rx
                                                                        :layer/ry ry
                                                                        :layer/name "Untitled"
                                                                        :db/id layer-id}])
                                (-> (if f
                                      (f states)
                                      states)
                                  (assoc-in [client :drawing] {})
                                  (update-in [other-client :subscribers (name client)]
                                             (fn [s]
                                               (-> s
                                                 (assoc :layers [])))))))))))

(defn add-rect [tick-state owner params]
  (add-shape tick-state owner (assoc params :type :rect)))

(defn add-circle [tick-state owner {:keys [end-x start-x end-y start-y] :as params}]
  (add-shape tick-state owner (assoc params
                                     :type :rect
                                     :rx (js/Math.abs (- end-x start-x))
                                     :ry (js/Math.abs (- end-y start-y)))))

(defn add-line [tick-state owner params]
  (add-shape tick-state owner (assoc params :type :line)))


(defn move-mouse [tick-state owner {:keys [start-tick end-tick client start-x end-x start-y end-y]}]
  (let [other-client (if (= :a client) :b :a)]
    (-> (reduce (fn [tick-state relative-tick]
                  (update-in tick-state [(+ start-tick relative-tick)]
                             (fn [f]
                               (fn [states]
                                 (let [ex (+ start-x (* relative-tick (/ (- end-x start-x) (- end-tick start-tick))))
                                       ey (+ start-y (* relative-tick (/ (- end-y start-y) (- end-tick start-tick))))]
                                   (-> (if f
                                         (f states)
                                         states)
                                     (update-in [other-client :subscribers (name client)]
                                                (fn [s]
                                                  (-> s
                                                    (assoc :mouse-position [ex ey]))))))))))
                tick-state
                (range 0 (inc (- end-tick start-tick))))
      (update-in [:max-ticks] (fnil max 0) end-tick))))

(defn create-animation [owner]
  (-> {:max-tick 0}
    (add-rect owner {:start-tick 20
                     :end-tick 40
                     :client :a
                     :start-x 20
                     :end-x 40
                     :start-y 20
                     :end-y 80})
    (add-rect owner {:start-tick 20
                     :end-tick 40
                     :client :b
                     :start-x 120
                     :end-x 40
                     :start-y 80
                     :end-y 20})
    (move-mouse owner {:start-tick 41
                       :end-tick 48
                       :client :b
                       :start-x 120
                       :end-x 200
                       :start-y 80
                       :end-y 100})
    (move-mouse owner {:start-tick 41
                       :end-tick 55
                       :client :a
                       :start-x 40
                       :end-x 350
                       :start-y 80
                       :end-y 400})
    (add-line owner {:start-tick 50
                     :end-tick 90
                     :client :b
                     :start-x 200
                     :end-x 300
                     :start-y 100
                     :end-y 200})
    (add-circle owner {:start-tick 60
                       :end-tick 90
                       :client :a
                       :start-x 350
                       :end-x 200
                       :start-y 400
                       :end-y 200})))

(defn test-sketch [_ owner]
  (reify
    om/IInitState
    (init-state [_]
      (assoc (initial-state)
             :db (ds/make-initial-db nil)
             :ticks (create-animation owner)))
    om/IDidMount
    (did-mount [_]
      (run-animation owner))
    om/IRenderState
    (render-state [_ {:keys [db states]}]
      (dom/div nil
        (dom/span nil (str @(om/get-state owner :last-tick)))
        (dom/div #js {:style #js {:display "flex"
                                  :flex-wrap "wrap"
                                  :padding-top 100}}

          (dom/div #js {:style #js {:height 500 :width "40%" :margin "1%"
                                    :flex-shrink "0"}}
            (om/build canvas/simple-canvas (assoc (:a states) :db db)))
          (dom/div #js {:style #js {:height 500 :width "40%" :margin "1%"
                                    :flex-shrink "0"}}
            (om/build canvas/simple-canvas (assoc (:b states) :db db))))))))
