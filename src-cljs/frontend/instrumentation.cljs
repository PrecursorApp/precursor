(ns frontend.instrumentation
  (:require [cljs-time.core :as time]
            [cljs-time.format :as time-format]
            [frontend.components.key-queue :as keyq]
            [frontend.datetime :as datetime]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [goog.dom]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

;; map of react-id to component render stats, e.g.
;; {"0.1.1" {:last-will-update <time 3pm> :display-name "App" :last-did-update <time 3pm> :render-ms [10 39 20 40]}}
(def component-stats (atom {}))

(defn wrap-will-update
  "Tracks last call time of componentWillUpdate for each component, then calls
   the original componentWillUpdate."
  [f]
  (fn [next-props next-state]
    (this-as this
             (let [display-name ((aget this "getDisplayName"))]
               (when-not display-name
                 (js/console.log this))
               (swap! component-stats update-in [(utils/react-id this)]
                      merge {:display-name ((aget this "getDisplayName"))
                             :last-will-update (time/now)}))
      (.call f this next-props next-state))))

(defn wrap-did-update
  "Tracks last call time of componentDidUpdate for each component and updates
   the render times (using start time provided by wrap-will-update), then
   calls the original componentDidUpdate."
  [f]
  (fn [prev-props prev-state]
    (this-as this
      (swap! component-stats update-in [(utils/react-id this)]
             (fn [stats]
               (let [now (time/now)]
                 (-> stats
                     (assoc :last-did-update now)
                     (update-in [:render-ms] (fnil conj [])
                                (if (and (:last-will-update stats)
                                         (time/after? now (:last-will-update stats)))
                                  (time/in-millis (time/interval (:last-will-update stats) now))
                                  0))))))
      (.call f this prev-props prev-state))))

(def instrumentation-methods
  (om/specify-state-methods!
   (-> om/pure-methods
     (update-in [:componentWillUpdate] wrap-will-update)
     (update-in [:componentDidUpdate] wrap-did-update)
     (clj->js))))

(defn avg [coll]
  (/ (reduce + coll)
     (count coll)))

(defn std-dev [coll]
  (let [a (avg coll)]
    (Math/sqrt (avg (map #(Math/pow (- % a) 2) coll)))))

(defn stats-view [data owner]
  (om/component
   (dom/figure nil
     (om/build keyq/keyboard-handler {:key-map {["ctrl+k"] #(om/transact! data (constantly {}))
                                                ["ctrl+j"] #(om/update-state! owner :shown? not)}
                                      :data {:key-map {["ctrl+k"] #(om/transact! data (constantly {}))
                                                       ["ctrl+j"] #(om/update-state! owner :shown? not)}}}
               {:opts {:cast! (fn [f] (f))}})

     (when (om/get-state owner :shown?)
       (let [stats (map (fn [[display-name renders]]
                          (let [times (mapcat :render-ms renders)]
                            {:display-name (or display-name "Unknown")
                             :render-count (count times)
                             :last-will-update (last (sort (map :last-will-update renders)))
                             :last-render-ms (last (:render-ms (last (sort-by :last-did-update renders))))
                             :average-render-ms (when (seq times) (int (avg times)))
                             :max-render-ms (when (seq times) (apply max times))
                             :min-render-ms (when (seq times) (apply min times))
                             :std-dev (when (seq times) (int (std-dev times)))}))
                        (reduce (fn [acc [react-id data]]
                                  (update-in acc [(:display-name data)] (fnil conj []) data))
                                {} data))]
         (dom/div #js {:className "admin-stats"}
           (dom/table nil
             (dom/caption nil "Component render stats, sorted by last update. Clicks go right through like it's not there. Ctrl+j to toggle, Ctrl+k to clear.")
             (dom/thead nil
               (dom/tr nil
                 (dom/th nil "component")
                 (dom/th #js {:className "number"} "count")
                 (dom/th #js {:className "number"} "last-update")
                 (dom/th #js {:className "number"} "last-ms")
                 (dom/th #js {:className "number"} "average-ms")
                 (dom/th #js {:className "number"} "max-ms")
                 (dom/th #js {:className "number"} "min-ms")
                 (dom/th #js {:className "number"} "std-ms")))
             (apply dom/tbody nil
                    (for [{:keys [display-name last-will-update average-render-ms
                                  max-render-ms min-render-ms std-dev render-count
                                  last-render-ms] :as stat} (reverse (sort-by :last-will-update stats))]
                      (dom/tr nil
                        (dom/td nil display-name)
                        (dom/td #js {:className "number" } render-count)
                        (dom/td #js {:className "number" }
                          (when last-will-update
                            (time-format/unparse (time-format/formatters :hour-minute-second)
                                                 last-will-update)))
                        (dom/td #js {:className "number" } last-render-ms)
                        (dom/td #js {:className "number" } average-render-ms)
                        (dom/td #js {:className "number" } max-render-ms)
                        (dom/td #js {:className "number" } min-render-ms)
                        (dom/td #js {:className "number" } std-dev)))))))))))

(defn setup-component-stats! []
  (let [stats-node (goog.dom/htmlToDocumentFragment "<div class='om-instrumentation'></div>")]
    (.appendChild (.-body js/document) stats-node)
    (om/root
     stats-view
     component-stats
     {:target stats-node})))
