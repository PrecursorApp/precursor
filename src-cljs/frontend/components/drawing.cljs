(ns frontend.components.drawing
  (:require [datascript :as d]
            [frontend.state :as state]
            [frontend.utils :as utils]
            [goog.dom]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

(defn add-tick [tick-state tick tick-fn]
  (update-in tick-state [:ticks tick] (fn [f] (fn [owner]
                                                (when f (f owner))
                                                (tick-fn owner)))))

(defn move-mouse [tick-state {:keys [start-tick end-tick start-x end-x start-y end-y tool]
                              :or {tool :rect}}]
  (reduce (fn [tick-state relative-tick]
            (add-tick tick-state
                      (+ start-tick relative-tick)
                      (fn [owner]
                        (let [ex (+ start-x (* relative-tick
                                               (/ (- end-x start-x)
                                                  (- end-tick start-tick))))
                              ey (+ start-y (* relative-tick
                                               (/ (- end-y start-y)
                                                  (- end-tick start-tick))))]
                          ((om/get-shared owner :cast!)
                           :subscriber-updated {:client-id (ffirst state/subscriber-bot)
                                                :fields {:mouse-position [ex ey]
                                                         :tool tool}})))))
          tick-state
          (range 0 (inc (- end-tick start-tick)))))

(defn draw-shape [tick-state {:keys [start-tick end-tick start-x end-x
                                     start-y end-y type tool doc-id props]
                              :or {type :rect
                                   tool :rect}}]
  (let [base-layer (merge {:layer/start-x start-x
                           :layer/start-y start-y
                           :layer/end-x end-x
                           :layer/end-y end-y
                           :layer/type (keyword "layer.type" (name type))
                           :layer/name "placeholder"
                           :layer/stroke-width 1
                           :document/id doc-id
                           :db/id (inc (rand-int 1000))}
                          props)
        ;; number of ticks to pause before saving layer
        pause-ticks 2]
    (-> (reduce (fn [tick-state relative-tick]
                  (add-tick tick-state
                            (+ start-tick relative-tick)
                            (fn [owner]
                              (let [ex (+ start-x (* relative-tick
                                                     (/ (- end-x start-x)
                                                        (- end-tick start-tick pause-ticks))))
                                    ey (+ start-y (* relative-tick
                                                     (/ (- end-y start-y)
                                                        (- end-tick start-tick pause-ticks))))]
                                ((om/get-shared owner :cast!)
                                 :subscriber-updated {:client-id (ffirst state/subscriber-bot)
                                                      :fields {:mouse-position [ex ey]
                                                               :layers [(assoc base-layer
                                                                               :layer/current-x ex
                                                                               :layer/current-y ey)]
                                                               :tool tool}})))))
                tick-state
                (range 0 (inc (- end-tick start-tick pause-ticks))))
      (add-tick end-tick
                (fn [owner]
                  ((om/get-shared owner :cast!)
                   :subscriber-updated {:client-id (ffirst state/subscriber-bot)
                                        :fields {:mouse-position nil
                                                 :layers nil
                                                 :tool tool}})
                  (d/transact! (om/get-shared owner :db) [base-layer] {:bot-layer true}))))))

(def text-height 14)

(defn draw-text [tick-state {:keys [start-tick end-tick start-x end-x
                                    start-y end-y doc-id text props]}]
  (let [base-layer (merge {:layer/start-x start-x
                           :layer/start-y start-y
                           :layer/end-x end-x
                           :layer/end-y end-y
                           :layer/type :layer.type/text
                           :layer/name "placeholder"
                           :layer/text text
                           :layer/stroke-width 1
                           :document/id doc-id
                           :db/id (inc (rand-int 1000))}
                          props)
        ;; number of ticks to pause before saving layer
        pause-ticks 2]
    (-> (reduce (fn [tick-state relative-tick]
                  (add-tick tick-state
                            (utils/inspect (+ start-tick relative-tick))
                            (fn [owner]
                              (let [letter-count (utils/inspect (int (/ (count text)
                                                                        (/ (- end-tick start-tick pause-ticks)
                                                                           relative-tick))))]
                                ((om/get-shared owner :cast!)
                                 :subscriber-updated {:client-id (ffirst state/subscriber-bot)
                                                      :fields {:mouse-position [start-x (- start-y (/ text-height 2))]
                                                               :layers [(assoc base-layer
                                                                               :layer/text (apply str (take letter-count text))
                                                                               :layer/current-x end-x
                                                                               :layer/current-y end-y)]
                                                               :tool :text}})))))
                tick-state
                (map int
                     (range 0
                            (inc (- end-tick start-tick pause-ticks))
                            (/ (- end-tick start-tick pause-ticks)
                               (count text)))))
      (add-tick end-tick
                (fn [owner]
                  ((om/get-shared owner :cast!)
                   :subscriber-updated {:client-id (ffirst state/subscriber-bot)
                                        :fields {:mouse-position nil
                                                 :layers nil
                                                 :tool :text}})
                  (d/transact! (om/get-shared owner :db) [base-layer] {:bot-layer true}))))))

(defn signup-animation [document vw]
  (let [text "Sign in with Google"
        text-width 171

        rect-width 208
        rect-height 48

        rect-offset-x 8
        rect-offset-y 8

        rect-start-x (- vw rect-width rect-offset-x)
        rect-start-y (+ rect-offset-y rect-height)
        rect-end-x (+ rect-start-x rect-width)
        rect-end-y rect-offset-y

        text-start-x (+ rect-start-x (/ (- rect-width text-width) 2))
        text-start-y (+ rect-end-y text-height (/ (- rect-height text-height) 2))]
    (-> {:tick-ms 16
         :ticks {}}
      (move-mouse {:start-tick 10
                   :end-tick 45
                   :start-x vw
                   :end-x rect-start-x
                   :start-y 0
                   :end-y rect-start-y})
      (draw-shape {:type :rect
                   :doc-id (:db/id document)
                   :tool :rect
                   :start-tick 50
                   :end-tick 100
                   :start-x rect-start-x
                   :end-x rect-end-x
                   :start-y rect-start-y
                   :end-y rect-end-y
                   :props {;; look into this later, right now it interfers with signup action
                           ;; :layer/ui-target "/signup"
                           :layer/signup-button true}})
      (move-mouse {:tool :text
                   :start-tick 100
                   :end-tick 120
                   :start-x rect-end-x
                   :end-x text-start-x
                   :start-y rect-end-y
                   :end-y (- text-start-y (/ text-height 2))})
      (draw-text {:text text
                  :doc-id (:db/id document)
                  :start-tick 120
                  :end-tick 200
                  :start-x text-start-x
                  :end-x (+ text-start-x text-width)
                  :start-y text-start-y
                  :end-y (- text-start-y text-height)
                  :props {;; look into this later, right now it interfers with signup action
                          ;;:layer/ui-target "/signup"
                          :layer/signup-button true}}))))

(defn run-animation* [owner tick-state max-tick current-tick]
  (when (and (om/mounted? owner)
             (>= max-tick current-tick))
    (when-let [tick-fn (get-in tick-state [:ticks current-tick])]
      (tick-fn owner))
    (js/setTimeout #(run-animation* owner tick-state max-tick (inc current-tick))
                   (:tick-ms tick-state))))

(defn run-animation
  "tick-state should be a map with keys :tick-ms, whose value is the number of ms between
  ticks, and :ticks, whose value is a map of tick-number to function to call on that tick.
  The function will be passed an owner.

  Example tick-state:
  {:tick-ms 16
   :ticks {0 #((om/get-shared % :cast!) :subscriber-updated {:client-id \"prcrsr-bot\" :fields {:mouse-position [1 2]}})
           5 #((om/get-shared % :cast!) :subscriber-updated {:client-id \"prcrsr-bot\" :fields {:mouse-position [2 3]}})

  Unmount the component to stop the animation."
  [owner tick-state]
  (let [max-tick (apply max (keys (:ticks tick-state)))]
    (run-animation* owner tick-state max-tick 0)))

(defn cleanup [owner]
  ((om/get-shared owner :cast!) :subscriber-updated {:client-id (ffirst state/subscriber-bot)
                                                     :fields {:mouse-position nil
                                                              :layers nil}}))

(defn signup-button [document owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (let [vw (.-width (goog.dom/getViewportSize))]
        (run-animation owner (signup-animation document vw))))
    om/IWillUnmount
    (will-unmount [_]
      (cleanup owner))
    om/IRender
    (render [_]
      ;; dummy span so that the component can be mounted
      (dom/span #js {:className "hidden"}))))
