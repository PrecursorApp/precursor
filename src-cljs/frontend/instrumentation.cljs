(ns frontend.instrumentation
  (:require [cljs-time.core :as time]
            [cljs-time.format :as time-format]
            [frontend.components.key-queue :as keyq]
            [frontend.datetime :as datetime]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [goog.dom]
            [goog.string :as gstring]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

;; map of react-id to component render stats, e.g.
;; {"0.1.1" {:last-will-update <time 3pm> :display-name "App" :last-did-update <time 3pm> :render-ms [10 39 20 40]}}
(defonce component-stats (atom {}))

(defn wrap-will-update
  "Tracks last call time of componentWillUpdate for each component, then calls
   the original componentWillUpdate."
  [f]
  (fn [next-props next-state]
    (this-as this
      (swap! component-stats update-in [(utils/react-id this)]
             merge {:display-name ((aget this "getDisplayName"))
                    :last-will-update (time/now)})
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

(defn wrap-will-mount
  "Tracks last call time of componentWillMount for each component, then calls
   the original componentWillMount."
  [f]
  (fn []
    (this-as this
      (swap! component-stats update-in [(utils/react-id this)]
             merge {:display-name ((aget this "getDisplayName"))
                    :last-will-mount (time/now)})
      (.call f this))))

(defn wrap-did-mount
  "Tracks last call time of componentDidMount for each component and updates
   the render times (using start time provided by wrap-will-mount), then
   calls the original componentDidMount."
  [f]
  (fn []
    (this-as this
      (swap! component-stats update-in [(utils/react-id this)]
             (fn [stats]
               (let [now (time/now)]
                 (-> stats
                   (assoc :last-did-mount now)
                   (update-in [:mount-ms] (fnil conj [])
                              (if (and (:last-will-mount stats)
                                       (time/after? now (:last-will-mount stats)))
                                (time/in-millis (time/interval (:last-will-mount stats) now))
                                0))))))
      (.call f this))))

(def instrumentation-methods
  (om/specify-state-methods!
   (-> om/pure-methods
     (update-in [:componentWillUpdate] wrap-will-update)
     (update-in [:componentDidUpdate] wrap-did-update)
     (update-in [:componentWillMount] wrap-will-mount)
     (update-in [:componentDidMount] wrap-did-mount)
     (clj->js))))

(defn avg [coll]
  (/ (reduce + coll)
     (count coll)))

(defn std-dev [coll]
  (let [a (avg coll)]
    (Math/sqrt (avg (map #(Math/pow (- % a) 2) coll)))))

(defn format-stat [mount-stat render-stat]
  (gstring/format "%s|%s"
                  (or mount-stat "-")
                  (if render-stat
                    (str (apply str (repeat (- 4 (count (str render-stat))) " "))
                         render-stat)
                    "   -")))

(defn compare-display-name [a b]
  (compare (:display-name b)
           (:display-name a)))

(defn compare-last-update [a b]
  (let [res (compare (max (:last-will-update a) (:last-will-mount a))
                     (max (:last-will-update b) (:last-will-mount b)))]
    (if (zero? res)
      (compare-display-name a b)
      res)))

(defn stats-view [data owner]
  (reify
    om/IInitState (init-state [_] {:shown? false
                                   :sort-orders (cycle [:last-update :display-name
                                                        :mount-count :render-count])})
    om/IRenderState
    (render-state [_ {:keys [shown? sort-orders]}]
      (dom/figure nil
        (om/build keyq/keyboard-handler {:key-map {#{"shift" "ctrl" "alt"  "k"} #(om/transact! data (constantly {}))
                                                   #{"shift" "ctrl" "alt" "j"} #(om/update-state! owner :shown? not)
                                                   #{"shift" "ctrl" "alt" "s"} #(om/update-state! owner :sort-orders rest)}})

        (when shown?
          (let [sort-order (first sort-orders)
                stats-compare (case sort-order
                                :last-update compare-last-update
                                :display-name compare-display-name
                                (fn [x y] (compare (sort-order x) (sort-order y))))
                stats (map (fn [[display-name renders]]
                             (let [render-times (filter identity (mapcat :render-ms renders))
                                   mount-times (filter identity (mapcat :mount-ms renders))]
                               {:display-name (or display-name "Unknown")
                                :render-count (count render-times)
                                :mount-count (count mount-times)

                                :last-will-update (last (sort (map :last-will-update renders)))
                                :last-will-mount (last (sort (map :last-will-mount renders)))

                                :last-render-ms (last (:render-ms (last (sort-by :last-did-update renders))))
                                :last-mount-ms (last (:mount-ms (last (sort-by :last-did-mount renders))))

                                :average-render-ms (when (seq render-times) (int (avg render-times)))
                                :average-mount-ms (when (seq mount-times) (int (avg mount-times)))

                                :max-render-ms (when (seq render-times) (apply max render-times))
                                :max-mount-ms (when (seq mount-times) (apply max mount-times))

                                :min-render-ms (when (seq render-times) (apply min render-times))
                                :min-mount-ms (when (seq mount-times) (apply min mount-times))

                                :render-std-dev (when (seq render-times) (int (std-dev render-times)))
                                :mount-std-dev (when (seq mount-times) (int (std-dev mount-times)))}))
                           (reduce (fn [acc [react-id data]]
                                     (update-in acc [(:display-name data)] (fnil conj []) data))
                                   {} data))]
            (dom/table #js {:className "instrumentation-table"}
              (dom/thead nil
                (dom/tr nil
                  (dom/th nil "component")
                  (dom/th #js {:className "number right"} "render ")
                  (dom/th #js {:className "number left"} "/ mount")
                  (dom/th #js {:className "number" :colSpan "2"} "last-ms")
                  (dom/th #js {:className "number" :colSpan "2"} "average-ms")
                  (dom/th #js {:className "number" :colSpan "2"} "max-ms")
                  (dom/th #js {:className "number" :colSpan "2"} "min-ms")
                  (dom/th #js {:className "number" :colSpan "2"} "std-ms")))
              (apply dom/tbody nil
                     (for [{:keys [display-name
                                   last-will-update last-will-mount
                                   average-render-ms average-mount-ms
                                   max-render-ms max-mount-ms
                                   min-render-ms min-mount-ms
                                   render-std-dev mount-std-dev
                                   render-count mount-count
                                   last-render-ms last-mount-ms] :as stat}
                           (reverse (sort stats-compare stats))]
                       (dom/tr nil
                         (dom/td nil display-name)
                         (dom/td #js {:className "number" } render-count)
                         (dom/td #js {:className "number" } (when mount-count (gstring/format "%2d" mount-count)))
                         (dom/td #js {:className "number" } last-mount-ms)
                         (dom/td #js {:className "number" } (when last-render-ms (gstring/format "%2d" last-render-ms)))
                         (dom/td #js {:className "number" } average-mount-ms)
                         (dom/td #js {:className "number" } (when average-render-ms (gstring/format "%2d" average-render-ms)))
                         (dom/td #js {:className "number" } max-mount-ms)
                         (dom/td #js {:className "number" } (when max-render-ms (gstring/format "%2d" max-render-ms)))
                         (dom/td #js {:className "number" } min-mount-ms)
                         (dom/td #js {:className "number" } (when min-render-ms (gstring/format "%2d" min-render-ms)))
                         (dom/td #js {:className "number" } mount-std-dev)
                         (dom/td #js {:className "number" } (when render-std-dev (gstring/format "%2d" render-std-dev))) )))
              (dom/tfoot nil
                (dom/tr nil
                  (dom/td #js {:className "instrumentation-info" :colSpan "13"}
                          (gstring/format "Component render stats, sorted by %s (Ctrl+Alt+Shift+s). Clicks go through. Ctrl+Alt+Shift+j to toggle, Ctrl+Alt+Shift+k to clear."
                                                                           sort-order)))))))))))

(defn prepend-stats-node []
  (let [node (goog.dom/htmlToDocumentFragment "<div class='om-instrumentation'></div>")
        body js/document.body]
    (.insertBefore body node (.-firstChild body))
    node))

(defn setup-component-stats! []
  (let [stats-node (or (goog.dom/getElementByClass "om-instrumentation")
                       (prepend-stats-node))]
    (om/root
     stats-view
     component-stats
     {:target stats-node})))
