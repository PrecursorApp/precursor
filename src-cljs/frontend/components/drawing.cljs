(ns frontend.components.drawing
  (:require [datascript :as d]
            [frontend.camera :as cameras]
            [frontend.db :as fdb]
            [frontend.db.trans :as trans]
            [frontend.state :as state]
            [frontend.svg :as svg]
            [frontend.utils :as utils]
            [goog.dom]
            [goog.style]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:import [goog.ui IdGenerator]))

(defn add-tick [tick-state tick tick-fn]
  (update-in tick-state [:ticks tick] (fn [f] (fn [owner]
                                                (when f (f owner))
                                                (tick-fn owner)))))

(defn annotate-keyframes [tick-state & ticks]
  (update-in tick-state [:keyframes] #(apply (fnil conj #{}) % ticks)))

(defn clear-subscriber [tick-state tick]
  (-> tick-state
    (add-tick tick (fn [owner]
                     ((om/get-shared owner :cast!)
                      :subscriber-updated {:client-id (:client-id (:bot tick-state))
                                           :fields (merge (:bot tick-state) {:mouse-position nil
                                                                                :tool nil
                                                                                :show-mouse? false})})))
    (annotate-keyframes tick)))

(defn move-mouse [tick-state {:keys [start-tick end-tick start-x end-x start-y end-y tool]
                              :or {tool :rect}}]
  (-> (reduce (fn [tick-state relative-tick]
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
                               :subscriber-updated {:client-id (:client-id (:bot tick-state))
                                                    :fields (merge (:bot tick-state) {:mouse-position [ex ey]
                                                                                         :show-mouse? true
                                                                                         :tool tool})})))))
              tick-state
              (range 0 (inc (- end-tick start-tick))))
    (annotate-keyframes end-tick)))

(defn props->base-layer [{:keys [start-tick end-tick start-x end-x
                                 start-y end-y tool doc-id props source]
                          :or {tool :rect}}]
  (merge {:layer/start-x start-x
          :layer/start-y start-y
          :layer/end-x end-x
          :layer/end-y end-y
          :layer/type (keyword "layer.type" (if (= :circle tool)
                                              "rect"
                                              (name tool)))
          :layer/name "placeholder"
          :layer/stroke-width 1
          :db/id (rand-int 10000)}
         (when (= :circle tool)
           {:layer/rx 1000
            :layer/ry 1000})
         (when source
           {:layer/source source})
         props))

(defn draw-shape [tick-state {:keys [start-tick end-tick start-x end-x
                                     start-y end-y tool doc-id props source]
                              :or {tool :rect}
                              :as properties}]
  (let [base-layer (props->base-layer properties)
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
                                 :subscriber-updated {:client-id (:client-id (:bot tick-state))
                                                      :fields (merge (:bot tick-state) {:mouse-position [ex ey]
                                                                                           :show-mouse? true
                                                                                           :layers [(assoc base-layer
                                                                                                           :layer/current-x ex
                                                                                                           :layer/current-y ey)]
                                                                                           :tool tool})})))))
                tick-state
                (range 0 (inc (- end-tick start-tick pause-ticks))))
      (add-tick end-tick
                (fn [owner]
                  ((om/get-shared owner :cast!)
                   :subscriber-updated {:client-id (:client-id (:bot tick-state))
                                        :fields {:mouse-position nil
                                                 :layers nil
                                                 :tool tool}})
                  (let [conn (om/get-shared owner :db)]
                    (d/transact! conn [(assoc base-layer :db/id (trans/get-next-transient-id conn))] {:bot-layer true}))))
      (annotate-keyframes start-tick end-tick))))

(defn move-points [points move-x move-y]
  (map (fn [{:keys [rx ry]}]
         {:rx (+ rx move-x)
          :ry (+ ry move-y)})
       points))

(defn move-layer [layer x y]
  (-> layer
    (assoc :layer/start-x (+ x (:layer/start-x layer))
           :layer/end-x (+ x (:layer/end-x layer))
           :layer/current-x (+ x (:layer/end-x layer))
           :layer/start-y (+ y (:layer/start-y layer))
           :layer/end-y (+ y (:layer/end-y layer))
           :layer/current-y (+ y (:layer/end-y layer)))
    (cond-> (= :layer.type/path (:layer/type layer))
      (assoc :layer/path (svg/points->path (move-points (:points layer) x y))))))

(defn alt-drag [tick-state {:keys [start-tick end-tick start-x end-x
                                   start-y end-y doc-id props source
                                   layers]
                            :as properties}]
  (let [x-distance (- end-x start-x)
        y-distance (- end-y start-y)
        base-layers (map (fn [p] (props->base-layer (merge properties p)))
                         layers)
        ;; number of ticks to pause before saving layers
        pause-ticks 2]
    (-> (reduce (fn [tick-state relative-tick]
                  (add-tick tick-state
                            (+ start-tick relative-tick)
                            (fn [owner]
                              (let [move-x (* relative-tick
                                              (/ x-distance
                                                 (- end-tick start-tick pause-ticks)))
                                    move-y (* relative-tick
                                              (/ y-distance
                                                 (- end-tick start-tick pause-ticks)))]
                                ((om/get-shared owner :cast!)
                                 :subscriber-updated {:client-id (:client-id (:bot tick-state))
                                                      :fields (merge (:bot tick-state) {:mouse-position [(+ start-x move-x)
                                                                                                            (+ start-y move-y)]
                                                                                           :show-mouse? true
                                                                                           :layers (map (fn [l] (move-layer l move-x move-y))
                                                                                                        base-layers)
                                                                                           :tool :select})})))))
                tick-state
                (range 0 (inc (- end-tick start-tick pause-ticks))))
      (add-tick end-tick
                (fn [owner]
                  ((om/get-shared owner :cast!)
                   :subscriber-updated {:client-id (:client-id (:bot tick-state))
                                        :fields {:mouse-position nil
                                                 :layers nil
                                                 :tool :select}})
                  (let [conn (om/get-shared owner :db)]
                    (d/transact! conn  (map (fn [l] (assoc (move-layer l x-distance y-distance)
                                                           :db/id (trans/get-next-transient-id conn)))
                                            base-layers)
                                 {:bot-layer true}))))
      (annotate-keyframes start-tick end-tick))))

(def text-height 14)

(defn draw-text [tick-state {:keys [start-tick end-tick start-x end-x
                                    start-y end-y doc-id text source props]}]
  (let [base-layer (merge {:layer/start-x start-x
                           :layer/start-y start-y
                           :layer/end-x end-x
                           :layer/end-y end-y
                           :layer/type :layer.type/text
                           :layer/name "placeholder"
                           :layer/text text
                           :layer/stroke-width 1
                           :db/id (inc (rand-int 1000))}
                          (when source
                            {:layer/source source})
                          props)
        ;; number of ticks to pause before saving layer
        pause-ticks 2]
    (-> (reduce (fn [tick-state relative-tick]
                  (add-tick tick-state
                            (+ start-tick relative-tick)
                            (fn [owner]
                              (let [letter-count (int (/ (count text)
                                                         (/ (- end-tick start-tick pause-ticks)
                                                            relative-tick)))]
                                ((om/get-shared owner :cast!)
                                 :subscriber-updated {:client-id (:client-id (:bot tick-state))
                                                      :fields (merge (:bot tick-state) {:mouse-position [start-x (- start-y (/ text-height 2))]
                                                                                           :show-mouse? true
                                                                                           :layers [(assoc base-layer
                                                                                                           :layer/text (apply str (take letter-count text))
                                                                                                           :layer/current-x end-x
                                                                                                           :layer/current-y end-y)]
                                                                                           :tool :text})})))))
                tick-state
                (map int
                     (range 0
                            (inc (- end-tick start-tick pause-ticks))
                            (/ (- end-tick start-tick pause-ticks)
                               (count text)))))
      (add-tick end-tick
                (fn [owner]
                  ((om/get-shared owner :cast!)
                   :subscriber-updated {:client-id (:client-id (:bot tick-state))
                                        :fields (merge (:bot tick-state) {:mouse-position nil
                                                                             :layers nil
                                                                             :tool :text})})
                  (d/transact! (om/get-shared owner :db) [base-layer] {:bot-layer true})))
      (annotate-keyframes start-tick end-tick))))

(defn clear-ticks [tick-state ticks]
  (update-in tick-state [:ticks] #(apply dissoc % ticks)))

(defn middle-elem [v]
  (if (seq v)
    (nth v (quot (count v) 2))
    nil))

(defn run-animation* [owner start-ms current-ms tick-state min-tick max-tick]
  (when (and (om/mounted? owner)
             (seq (:ticks tick-state)))
    (let [latest-tick (int (/ (- current-ms start-ms)
                              (:tick-ms tick-state)))
          tick-range (vec (range min-tick (inc latest-tick)))
          ticks (-> (filter #(contains? (:keyframes tick-state) %) tick-range)
                  set
                  (conj (middle-elem tick-range)))]
      (doseq [tick (sort ticks)]
        (when-let [tick-fn (get-in tick-state [:ticks tick])]
          (tick-fn owner)))
      (utils/rAF (fn [timestamp]
                   (run-animation* owner start-ms timestamp (clear-ticks tick-state tick-range) latest-tick max-tick))))))

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
    (utils/rAF (fn [timestamp]
                 (run-animation* owner timestamp timestamp tick-state 0 max-tick)))))

(defn cleanup [tick-state owner]
  ((om/get-shared owner :cast!) :subscriber-updated {:client-id (:client-id (:bot tick-state))
                                                     :fields (merge (:bot tick-state) {:mouse-position nil
                                                                                       :layers nil
                                                                                       :show-mouse? false})}))

(defn signup-animation [document top-right]
  (let [text "Sign in with Google"
        text-width 171

        rect-width 208
        rect-height 48

        rect-offset-x 8
        rect-offset-y 8

        ;; e stands for edge
        [ex ey] top-right

        rect-start-x (- ex rect-width rect-offset-x)
        rect-start-y (+ ey (+ rect-offset-y rect-height))
        rect-end-x (+ rect-start-x rect-width)
        rect-end-y (+ ey rect-offset-y)

        text-start-x (+ rect-start-x (/ (- rect-width text-width) 2))
        text-start-y (+ rect-end-y text-height (/ (- rect-height text-height) 2))]
    (-> {:tick-ms 16
         :bot state/subscriber-bot
         :ticks {}}
      (move-mouse {:tool :text
                   :start-tick 10
                   :end-tick 45
                   :start-x ex :end-x text-start-x
                   :start-y ey :end-y (- text-start-y (/ text-height 2))})
      (draw-text {:text text
                  :doc-id (:db/id document)
                  :start-tick 50
                  :end-tick 130
                  :start-x text-start-x :end-x (+ text-start-x text-width)
                  :start-y text-start-y :end-y (- text-start-y text-height)
                  :source "signup-animation"
                  :props {:layer/signup-button true}})
      (move-mouse {:start-tick 140
                   :end-tick 145
                   :start-x text-start-x :end-x rect-start-x
                   :start-y text-start-y :end-y rect-start-y})
      (draw-shape {:doc-id (:db/id document)
                   :tool :rect
                   :start-tick 150
                   :end-tick 200
                   :start-x rect-start-x :end-x rect-end-x
                   :start-y rect-start-y :end-y rect-end-y
                   :source "signup-animation"
                   :props {:layer/ui-target "/signup"
                           :layer/ui-id "/signup"
                           :layer/signup-button true}})
      (clear-subscriber 201))))

(defn signup-button [{:keys [document camera]} owner]
  (reify
    om/IDisplayName (display-name [_] "Signup Button Animation")
    om/IDidMount
    (did-mount [_]
      ;; TODO: would be nice to get this a different way :(
      (let [viewport (utils/canvas-size)]
        (when (and (< 640 (:width viewport)) ;; only if not on mobile
                   (empty? (d/datoms @(om/get-shared owner :db) :avet :layer/source "signup-animation")))
          (run-animation owner (signup-animation document (cameras/top-right camera viewport))))))
    om/IWillUnmount
    (will-unmount [_]
      (cleanup {:bot state/subscriber-bot} owner))
    om/IRender
    (render [_]
      ;; dummy span so that the component can be mounted
      (dom/span #js {:className "hidden"}))))

(defn add-shape [tick-state tick-count props]
  (draw-shape tick-state (merge (let [pause (+ 3 (inc (rand-int 5)))
                                      start-tick (+ pause (apply max (conj (keys (:ticks tick-state)) -1)))]
                                  {:start-tick start-tick
                                   :end-tick (+ start-tick (int (* 1.5 tick-count)))})
                                props)))

(defn add-alt-drag [tick-state tick-count props]
  (alt-drag tick-state (merge (let [pause (+ 3 (inc (rand-int 5)))
                                    start-tick (+ pause (apply max (conj (keys (:ticks tick-state)) -1)))]
                                {:start-tick start-tick
                                 :end-tick (+ start-tick (int (* 1.5 tick-count)))})
                              props)))

(defn add-text [tick-state tick-count props]
  (draw-text tick-state (merge (let [pause (+ 3 (inc (rand-int 5)))
                                     start-tick (+ pause (apply max (conj (keys (:ticks tick-state)) -1)))]
                                 {:start-tick start-tick
                                  :end-tick (+ start-tick (int (* 1.5 tick-count)))})
                               props)))

(defn add-mouse-transition [tick-state tick-count previous-shape next-shape]
  (move-mouse tick-state (let [pause (+ 2 (inc (rand-int 4)))
                               start-tick (+ pause (apply max (conj (keys (:ticks tick-state)) -1)))]
                           {:start-tick start-tick
                            :end-tick (+ start-tick (int (* 1.5 tick-count)))
                            :start-x (:end-x previous-shape)
                            :start-y (:end-y previous-shape)
                            :tool (:tool next-shape)
                            :end-x (:start-x next-shape)
                            :end-y (:start-y next-shape)})))

(defn browser [layer-source viewport]
  (let [width 800
        height 600
        start-y (/ (- (:height viewport) height)
                   3)
        start-x (/ (- (:width viewport) width)
                   2)]
    {:tool :rect
     :start-x start-x :end-x (+ start-x width)
     :start-y start-y :end-y (+ start-y height)
     :source layer-source}))

(defn margins-box [layer-source viewport]
  (let [browser (browser layer-source viewport)
        width 600]
    {:tool :rect
     :start-x (- (:end-x browser) 100)
     :end-x (- (:end-x browser) 100 600)
     :start-y (:end-y browser)
     :end-y (:start-y browser)
     :source layer-source}))

(defn header-line [layer-source viewport]
  (let [browser (browser layer-source viewport)]
    {:tool :line
     :start-x (:start-x browser)
     :end-x (:end-x browser)
     :start-y (+ (:start-y browser) 50)
     :end-y (+ (:start-y browser) 50)
     :source layer-source}))

(defn story-one-a [layer-source viewport]
  (let [browser (browser layer-source viewport)]
    {:tool :rect
     :start-x (+ (:start-x browser) 100 50)
     :end-x (+ (:start-x browser) 100 50 480)
     :start-y (+ (:start-y browser) 160)
     :end-y (+ (:start-y browser) 160 5)
     :source layer-source}))

(defn story-one-b [layer-source viewport]
  (let [browser (browser layer-source viewport)]
    {:tool :rect
     :start-x (+ (:start-x browser) 100 50)
     :end-x (+ (:start-x browser) 100 50 310)
     :start-y (+ (:start-y browser) 190)
     :end-y (+ (:start-y browser) 190 -5) ; go opposite
     :source layer-source}))

(defn story-two-a [layer-source viewport]
  (let [browser (browser layer-source viewport)]
    {:tool :line
     :start-x (+ (:start-x browser) 100 50)
     :end-x (+ (:start-x browser) 100 50 500)
     :start-y (+ (:start-y browser) 300)
     :end-y (+ (:start-y browser) 300)
     :source layer-source}))

(defn story-two-b [layer-source viewport]
  (let [browser (browser layer-source viewport)]
    {:tool :rect
     :start-x (+ (:start-x browser) 100 50)
     :end-x (+ (:start-x browser) 100 50 280)
     :start-y (+ (:start-y browser) 340)
     :end-y (+ (:start-y browser) 340 -5)
     :source layer-source}))

(defn story-two-c [layer-source viewport]
  (let [browser (browser layer-source viewport)]
    {:tool :rect
     :start-x (+ (:start-x browser) 100 50)
     :end-x (+ (:start-x browser) 100 50 255)
     :start-y (+ (:start-y browser) 360)
     :end-y (+ (:start-y browser) 360 5)
     :source layer-source}))

(defn story-two-d [layer-source viewport]
  (let [browser (browser layer-source viewport)]
    {:tool :line
     :start-x (+ (:start-x browser) 100 50)
     :end-x (+ (:start-x browser) 100 50 500)
     :start-y (+ (:start-y browser) 400)
     :end-y (+ (:start-y browser) 400)
     :source layer-source}))

(defn circle-one [layer-source viewport]
  (let [browser (browser layer-source viewport)]
    {:tool :circle
     :start-x (+ (:start-x browser) 80)
     :end-x (+ (:start-x browser) 80 40)
     :start-y (+ (:start-y browser) 330)
     :end-y (+ (:start-y browser) 330 40)
     :source layer-source}))

(defn landing-animation [layer-source viewport]
  (let [browser (browser layer-source viewport)
        margins-box (margins-box layer-source viewport)
        header-line (header-line layer-source viewport)
        story-one-a (story-one-a layer-source viewport)
        story-one-b (story-one-b layer-source viewport)
        story-two-a (story-two-a layer-source viewport)
        story-two-b (story-two-b layer-source viewport)
        story-two-c (story-two-c layer-source viewport)
        story-two-d (story-two-d layer-source viewport)
        circle-one (circle-one layer-source viewport)
        story-one-layers [story-one-a story-one-b]
        story-two-layers [story-two-a story-two-b story-two-c story-two-d]]
    (-> {:tick-ms 16
         :bot state/subscriber-bot
         :ticks {}}
      (add-tick 50 identity)
      ;; the mouse could be automatic
      (add-mouse-transition 20
                            {:end-x 0
                             :end-y 0
                             :tool :rect}
                            browser)
      (add-shape 50 browser)
      (add-mouse-transition 7 browser margins-box)
      (add-shape 40 margins-box)
      (add-mouse-transition 7 margins-box header-line)
      (add-shape 25 header-line)
      (add-mouse-transition 10 header-line story-one-a)
      (add-shape 15 story-one-a)
      (add-mouse-transition 5 story-one-a story-one-b)
      (add-shape 15 story-one-b)
      (add-mouse-transition 15 story-one-b story-two-a)
      (add-shape 18 story-two-a)
      (add-mouse-transition 5 story-two-a story-two-b)
      (add-shape 10 story-two-b)
      (add-mouse-transition 7 story-two-b story-two-c)
      (add-shape 10 story-two-c)
      (add-mouse-transition 5 story-two-c story-two-d)
      (add-shape 18 story-two-d)

      (add-mouse-transition 10 story-two-d {:tool :select
                                           :start-x (+ 100 (:start-x story-one-b))
                                           :start-y (:start-y story-one-b)})
      (add-alt-drag 18
                    {:layers story-one-layers
                     :start-x (+ 100 (:start-x story-one-b))
                     :end-x (+ 100 (:start-x story-one-b))
                     :start-y (:start-y story-one-b)
                     :end-y (+ (:start-y story-one-b) 275)})

      (add-mouse-transition 7
                            {:tool :select
                             :end-x (+ 100 (:start-x story-one-b))
                             :end-y (+ (:start-y story-one-b) 275)}
                            {:tool :select
                             :start-x (+ 100 (:start-x story-two-d))
                             :start-y (:start-y story-two-d)})

      (add-alt-drag 18
                    {:layers story-two-layers
                     :start-x (+ 100 (:start-x story-two-d))
                     :end-x (+ 100 (:start-x story-two-d))
                     :start-y (:start-y story-two-d)
                     :end-y (+ (:start-y story-two-d) 200)})

      (add-mouse-transition 8
                            {:tool :select
                             :end-x (+ 100 (:start-x story-two-d))
                             :end-y (+ (:start-y story-two-d) 200)}
                            circle-one)
      (add-shape 10 circle-one)
      (add-alt-drag 18
                    {:layers [circle-one]
                     :start-x (:end-x circle-one)
                     :end-x (:end-x circle-one)
                     :start-y (:end-y circle-one)
                     :end-y (+ (:end-y circle-one) 200)}))))

(defn add-landing-cleanup [tick-state]
  (let [max-tick (apply max (keys (:ticks tick-state)))]
    (-> tick-state
      (add-tick (inc max-tick) (fn [owner]
                                 (cleanup {:bot state/subscriber-bot} owner)
                                 ((om/get-shared owner :cast!) :landing-animation-completed)))
      (annotate-keyframes (inc max-tick)))))

(defn landing-background [{:keys [subscribers]} owner]
  (reify
    om/IDisplayName (display-name [_] "Landing Animation")
    om/IInitState (init-state [_] {:layer-source (utils/uuid)})
    om/IDidMount
    (did-mount [_]
      ;; TODO: would be nice to get this a different way :(
      (let [viewport (utils/canvas-size)]
        (when (and (< 800 (:width viewport)) ;; only if not on mobile
                   (< 600 (:height viewport))
                   (fdb/empty-db? @(om/get-shared owner :db))
                   (zero? (count (remove (comp :hide-in-list? second) subscribers))))
          (run-animation owner (add-landing-cleanup (landing-animation (om/get-state owner :layer-source)
                                                                       viewport))))))
    om/IWillUnmount
    (will-unmount [_]
      (cleanup {:bot state/subscriber-bot} owner)
      (let [conn (om/get-shared owner :db)
            source (om/get-state owner :layer-source)]
        (d/transact! conn (mapv (fn [e] [:db/add e :layer/deleted true])
                                (map :e (d/datoms @conn :avet :layer/source source)))
                     {:bot-layer true})
        (js/setTimeout #(d/transact! conn (mapv (fn [e] [:db.fn/retractEntity e])
                                                (map :e (d/datoms @conn :avet :layer/source source)))
                                     {:bot-layer true})
                       1000)))

    om/IRender
    (render [_]
      ;; dummy span so that the component can be mounted
      (dom/span #js {:className "hidden"}))))
